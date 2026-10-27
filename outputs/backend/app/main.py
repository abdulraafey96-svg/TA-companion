import hashlib
import io
import os
import time
from typing import Any

from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from pypdf import PdfReader
import fitz
from dotenv import load_dotenv

from .analyzers import get_analyzer

load_dotenv()
app = FastAPI(title="Concept Companion API", version="1.0")
_cache: dict[str, tuple[float, dict[str, Any]]] = {}
MAX_BYTES = int(os.getenv("MAX_UPLOAD_MB", "15")) * 1024 * 1024
ALLOWED_IMAGES = {"image/jpeg", "image/png", "image/webp"}


def inspect_document(filename: str, mime: str, data: bytes) -> str:
    if not data:
        raise HTTPException(400, "The uploaded file is empty.")
    if len(data) > MAX_BYTES:
        raise HTTPException(413, f"Files must be no larger than {MAX_BYTES // (1024 * 1024)} MB.")
    if mime == "application/pdf" or filename.lower().endswith(".pdf"):
        try:
            reader = PdfReader(io.BytesIO(data), strict=True)
            if reader.is_encrypted:
                raise HTTPException(400, "Password-protected PDFs are not supported.")
            max_pages = int(os.getenv("MAX_PDF_PAGES", "20"))
            if len(reader.pages) == 0 or len(reader.pages) > max_pages:
                raise HTTPException(400, f"PDFs must contain 1 to {max_pages} pages.")
            text = "\n".join(page.extract_text() or "" for page in reader.pages).strip()
            return text
        except HTTPException:
            raise
        except Exception as exc:
            raise HTTPException(400, "Could not read this PDF.") from exc
    if mime not in ALLOWED_IMAGES:
        raise HTTPException(415, "Supported formats are PDF, JPEG, PNG, and WebP.")
    try:
        with Image.open(io.BytesIO(data)) as image:
            actual_mime = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}.get(image.format)
            if actual_mime != mime:
                raise HTTPException(415, "The file contents do not match a supported JPEG, PNG, or WebP image type.")
            image.verify()
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError) as exc:
        raise HTTPException(400, "The image file is invalid or damaged.") from exc
    return ""


@app.get("/health")
async def health():
    return {"status": "ok", "analyzer": os.getenv("ANALYZER", "demo")}


@app.post("/analyze")
async def analyze(file: UploadFile = File(...)):
    mime = (file.content_type or "").split(";")[0].lower()
    filename = file.filename or "upload"
    # Read bounded chunks to avoid trusting Content-Length.
    chunks, size = [], 0
    while True:
        chunk = await file.read(min(1024 * 1024, MAX_BYTES + 1 - size))
        if not chunk:
            break
        chunks.append(chunk); size += len(chunk)
        if size > MAX_BYTES:
            raise HTTPException(413, f"Files must be no larger than {MAX_BYTES // (1024 * 1024)} MB.")
    data = b"".join(chunks)
    extracted = inspect_document(filename, mime, data)
    digest = hashlib.sha256(data).hexdigest()
    cached = _cache.get(digest)
    if cached and cached[0] > time.time():
        return {**cached[1], "cached": True}
    if mime == "application/pdf" or filename.lower().endswith(".pdf"):
        try:
            pdf = fitz.open(stream=data, filetype="pdf")
            pages = []
            for page in pdf:
                scale = min(1.5, 1800 / max(page.rect.width, page.rect.height))
                pages.append(("image/png", page.get_pixmap(matrix=fitz.Matrix(scale, scale), alpha=False).tobytes("png")))
            pdf.close()
        except Exception as exc:
            raise HTTPException(400, "Could not render PDF pages for analysis.") from exc
    else:
        pages = [(mime, data)]
    try:
        result = await get_analyzer().analyze(filename, pages, extracted)
    except Exception as exc:
        import httpx
        if isinstance(exc, httpx.TimeoutException):
            raise HTTPException(504, "The AI provider timed out. Please try again.") from exc
        raise HTTPException(502, f"The AI provider could not analyze this file: {exc}") from exc
    response = {"documentId": digest[:20], "explanation": result, "cached": False}
    _cache[digest] = (time.time() + 3600, response)
    return response
