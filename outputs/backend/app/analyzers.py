import base64
import json
import os
from typing import Protocol

import httpx


class DocumentAnalyzer(Protocol):
    async def analyze(self, filename: str, pages: list[tuple[str, bytes]], extracted_text: str = "") -> dict: ...


class DemoAnalyzer:
    async def analyze(self, filename: str, pages: list[tuple[str, bytes]], extracted_text: str = "") -> dict:
        return {
            "title": "A guided look at your study notes",
            "plainEnglish": "This demo explanation shows where the AI explanation will appear. Connect a vision provider to discuss the actual contents of your uploaded page.",
            "intuition": "Start by asking what each symbol or statement is trying to describe, then connect it to a familiar example.",
            "workedExample": "For a simple equation such as x + 2 = 5, subtract 2 from both sides to get x = 3. The same operation on both sides preserves equality.",
            "question": "What operation would undo the last step in an equation you are studying?",
            "confirmedContent": extracted_text[:1500] or "Demo mode does not transcribe the uploaded page.",
            "uncertainTranscription": "No handwriting recognition was performed in demo mode.",
        }


class OpenAICompatibleAnalyzer:
    """OpenAI Chat Completions compatible vision provider, isolated behind DocumentAnalyzer."""
    async def analyze(self, filename: str, pages: list[tuple[str, bytes]], extracted_text: str = "") -> dict:
        key = os.getenv("OPENAI_API_KEY", "")
        if not key:
            raise RuntimeError("OPENAI_API_KEY is required when ANALYZER=openai")
        model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
        image_content = [{"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64.b64encode(data).decode()}"}} for mime, data in pages]
        prompt = (
            "Explain this university study page in plain English. Preserve math notation. "
            "Separate confirmed transcription from uncertain transcription. Give intuition, one worked example, "
            "and optionally one comprehension question. Return only JSON with keys title, plainEnglish, intuition, "
            "workedExample, question, confirmedContent, uncertainTranscription. "
            f"Selectable PDF text, if any: {extracted_text[:6000]}"
        )
        payload = {"model": model, "temperature": 0.2, "response_format": {"type": "json_object"}, "messages": [{"role": "user", "content": [{"type": "text", "text": prompt}, *image_content]}]}
        timeout = float(os.getenv("PROVIDER_TIMEOUT_SECONDS", "45"))
        async with httpx.AsyncClient(timeout=timeout) as client:
            response = await client.post("https://api.openai.com/v1/chat/completions", headers={"Authorization": f"Bearer {key}"}, json=payload)
            response.raise_for_status()
        return json.loads(response.json()["choices"][0]["message"]["content"])


def get_analyzer() -> DocumentAnalyzer:
    if os.getenv("ANALYZER", "demo").lower() == "openai":
        return OpenAICompatibleAnalyzer()
    return DemoAnalyzer()
