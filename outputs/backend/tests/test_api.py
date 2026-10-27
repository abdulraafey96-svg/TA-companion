import io
import os

import pytest
from fastapi.testclient import TestClient
from PIL import Image
import fitz

from app.main import app, _cache

client = TestClient(app)


def png_bytes():
    im = Image.new("RGB", (32, 32), "white")
    out = io.BytesIO(); im.save(out, "PNG"); return out.getvalue()


def test_image_demo_succeeds_and_caches(monkeypatch):
    monkeypatch.setenv("ANALYZER", "demo"); _cache.clear()
    first = client.post("/analyze", files={"file": ("page.png", png_bytes(), "image/png")})
    second = client.post("/analyze", files={"file": ("page.png", png_bytes(), "image/png")})
    assert first.status_code == 200
    assert first.json()["explanation"]["workedExample"]
    assert second.json()["cached"] is True


def test_missing_file():
    assert client.post("/analyze").status_code == 422


def test_unsupported_type():
    response = client.post("/analyze", files={"file": ("notes.txt", b"hello", "text/plain")})
    assert response.status_code == 415


def test_invalid_image():
    response = client.post("/analyze", files={"file": ("broken.png", b"not an image", "image/png")})
    assert response.status_code == 400


def test_pdf_with_selectable_text(monkeypatch):
    monkeypatch.setenv("ANALYZER", "demo"); _cache.clear()
    doc = fitz.open(); page = doc.new_page(); page.insert_text((72, 72), "Newton's second law: F = ma")
    data = doc.tobytes(); doc.close()
    response = client.post("/analyze", files={"file": ("lecture.pdf", data, "application/pdf")})
    assert response.status_code == 200
    assert "Newton" in response.json()["explanation"]["confirmedContent"]


def test_corrupt_pdf_is_rejected():
    response = client.post("/analyze", files={"file": ("broken.pdf", b"not a pdf", "application/pdf")})
    assert response.status_code == 400


def test_provider_error_is_reported_as_gateway_error(monkeypatch):
    monkeypatch.setenv("ANALYZER", "openai"); monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.post("/analyze", files={"file": ("page.png", png_bytes(), "image/png")})
    assert response.status_code == 502
    assert "OPENAI_API_KEY" in response.json()["detail"]
