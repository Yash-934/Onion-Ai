import os
import json
from typing import Any, Dict, List, Optional
from fastapi import FastAPI, Header, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.exceptions import RequestValidationError

from config import settings
from auth import is_authorized, is_admin_authorized, get_keystore
from privacy import logger, structured_error
from provider import ModelProvider, UpstreamError

app = FastAPI(
    title="Private AI Gateway",
    description="Privacy-preserving, Tor-first API Gateway compatible with Anthropic Messages API and OpenAI formats",
    version="1.0.0",
    docs_url=None,
    redoc_url=None,
    openapi_url=None
)

# --- MIDDLEWARE & SECURITY CHECKS ---

@app.middleware("http")
async def security_middleware(request: Request, call_next):
    # Content-Length check to prevent denial of service
    content_length = request.headers.get("content-length")
    if content_length and int(content_length) > settings.max_request_bytes:
        return JSONResponse(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            content=structured_error(413, "invalid_request_error", "Request payload exceeds maximum allowed size.")
        )
    
    response = await call_next(request)
    # Add anti-fingerprinting & security headers
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["X-Frame-Options"] = "DENY"
    response.headers["Cache-Control"] = "no-store, no-cache, must-revalidate"
    return response

# --- EXCEPTION HANDLERS (ZERO LEAKAGE) ---

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    return JSONResponse(
        status_code=status.HTTP_400_BAD_REQUEST,
        content=structured_error(400, "invalid_request_error", "Malformed request payload.")
    )

@app.exception_handler(HTTPException)
async def http_exception_handler(request: Request, exc: HTTPException):
    err_type = "authentication_error" if exc.status_code in (401, 403) else ("rate_limit_error" if exc.status_code == 429 else "invalid_request_error")
    return JSONResponse(
        status_code=exc.status_code,
        content=structured_error(exc.status_code, err_type, str(exc.detail))
    )

@app.exception_handler(UpstreamError)
async def upstream_exception_handler(request: Request, exc: UpstreamError):
    return JSONResponse(
        status_code=exc.status_code,
        content=structured_error(exc.status_code, exc.error_type, exc.message)
    )

@app.exception_handler(Exception)
async def generic_exception_handler(request: Request, exc: Exception):
    # Never leak internal traceback to clients
    return JSONResponse(
        status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
        content=structured_error(500, "api_error", "An internal server error occurred.")
    )

def authenticate(authorization: Optional[str], x_api_key: Optional[str]):
    ok, err_msg = is_authorized(authorization, x_api_key)
    if not ok:
        if "Rate limit" in err_msg:
            raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=err_msg)
        elif "revoked" in err_msg or "disabled" in err_msg:
            raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail=err_msg)
        else:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=err_msg)

def authenticate_admin(authorization: Optional[str], x_api_key: Optional[str]):
    ok, err_msg = is_admin_authorized(authorization, x_api_key)
    if not ok:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=err_msg)

# --- ADMIN KEY MANAGEMENT ENDPOINTS ---

@app.post("/admin/keys")
async def create_api_key(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    authenticate_admin(authorization, x_api_key)
    try:
        body = await request.json() if (await request.body()) else {}
    except Exception:
        body = {}
    
    name = body.get("name", "")
    expires_days = body.get("expires_in_days")
    rate_limit = body.get("rate_limit")

    metadata, secret = get_keystore().create_key(name=name, expires_in_days=expires_days, rate_limit=rate_limit)
    return {
        "success": True,
        "key": secret,
        "key_metadata": metadata,
        "warning": "The plaintext API key is only shown once upon creation. Store it securely."
    }

@app.get("/admin/keys")
async def list_api_keys(
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    authenticate_admin(authorization, x_api_key)
    keys = get_keystore().list_keys()
    return {"object": "list", "data": keys}

@app.delete("/admin/keys/{key_id}")
@app.post("/admin/keys/{key_id}/revoke")
async def revoke_api_key(
    key_id: str,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    authenticate_admin(authorization, x_api_key)
    revoked = get_keystore().revoke_key(key_id)
    if not revoked:
        raise HTTPException(status_code=404, detail=f"Key {key_id} not found.")
    return {"success": True, "id": key_id, "status": "revoked"}

@app.post("/admin/keys/{key_id}/rotate")
async def rotate_api_key(
    key_id: str,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    authenticate_admin(authorization, x_api_key)
    res = get_keystore().rotate_key(key_id)
    if not res:
        raise HTTPException(status_code=404, detail=f"Key {key_id} not found.")
    metadata, new_secret = res
    return {
        "success": True,
        "key": new_secret,
        "key_metadata": metadata,
        "warning": "The rotated plaintext API key is only shown once. Update PocketForge or client config."
    }

# --- ENDPOINTS ---

@app.get("/")
@app.get("/health")
async def health():
    return {
        "status": "ok",
        "service": "private-ai-gateway",
        "chat_model": settings.chat_model,
        "upstream_protocol": settings.upstream_protocol,
        "tor_policy": "strict-onion-only"
    }

@app.get("/models")
@app.get("/v1/models")
async def list_models(
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    authenticate(authorization, x_api_key)
    
    data = [
        {"id": settings.chat_model, "object": "model", "owned_by": "private-gateway"},
    ]
    if settings.code_model and settings.code_model != settings.chat_model:
        data.append({"id": settings.code_model, "object": "model", "owned_by": "private-gateway"})
    if settings.image_model:
        data.append({"id": settings.image_model, "object": "model", "owned_by": "private-gateway"})

    return {
        "object": "list",
        "data": data,
        "has_more": False
    }

@app.post("/v1/messages")
async def anthropic_messages(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    """
    Anthropic Messages API endpoint used by Claude Code / PocketForge.
    Supports system, messages, tools, tool_choice, tool_use, tool_result, and streaming.
    """
    authenticate(authorization, x_api_key)
    
    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON in request body.")

    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="Request body must be a JSON object.")

    if "messages" not in body or not isinstance(body["messages"], list):
        raise HTTPException(status_code=400, detail="'messages' array is required in request body.")

    is_stream = bool(body.get("stream", False))

    if is_stream:
        stream_gen = ModelProvider.handle_anthropic_messages_stream(body)
        return StreamingResponse(
            stream_gen,
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "Content-Type": "text/event-stream",
                "X-Accel-Buffering": "no"
            }
        )
    else:
        resp_data = await ModelProvider.handle_anthropic_messages_non_stream(body)
        return JSONResponse(status_code=200, content=resp_data)

@app.post("/chat/completions")
@app.post("/v1/chat/completions")
async def openai_chat_completions(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    """
    OpenAI-compatible /chat/completions endpoint.
    """
    authenticate(authorization, x_api_key)

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON in request body.")

    if not isinstance(body, dict):
        raise HTTPException(status_code=400, detail="Request body must be a JSON object.")

    is_stream, result = await ModelProvider.handle_openai_chat_completions(body)

    if is_stream:
        return StreamingResponse(
            result,
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "Content-Type": "text/event-stream",
                "X-Accel-Buffering": "no"
            }
        )
    else:
        return JSONResponse(status_code=200, content=result)

@app.post("/images/generations")
@app.post("/v1/images/generations")
async def images_generations(
    request: Request,
    authorization: Optional[str] = Header(default=None),
    x_api_key: Optional[str] = Header(default=None)
):
    """
    Image generation endpoint.
    """
    authenticate(authorization, x_api_key)

    try:
        body = await request.json()
    except Exception:
        raise HTTPException(status_code=400, detail="Invalid JSON in request body.")

    result = await ModelProvider.handle_image_generation(body)
    return JSONResponse(status_code=200, content=result)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8443)
