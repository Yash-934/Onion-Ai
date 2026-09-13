import os
import secrets
from typing import Optional

class Settings:
    def __init__(self):
        self.gateway_api_key: str = os.environ.get("GATEWAY_API_KEY", "").strip()
        self.gateway_admin_key: str = os.environ.get("GATEWAY_ADMIN_KEY", "").strip() or self.gateway_api_key
        self.keys_file: str = os.environ.get("KEYS_FILE", os.path.join(os.path.dirname(__file__), "api_keys.json"))
        self.rate_limit_per_minute: int = int(os.environ.get("RATE_LIMIT_PER_MINUTE", "60"))
        self.upstream_base_url: str = os.environ.get("UPSTREAM_BASE_URL", "").rstrip("/")
        self.upstream_api_key: str = os.environ.get("UPSTREAM_API_KEY", "").strip()
        self.upstream_protocol: str = os.environ.get("UPSTREAM_PROTOCOL", "openai_chat").strip().lower()
        self.anthropic_version: str = os.environ.get("ANTHROPIC_VERSION", "2023-06-01").strip()
        
        self.chat_model: str = os.environ.get("CHAT_MODEL", "private-chat-model").strip()
        self.code_model: str = os.environ.get("CODE_MODEL", "private-code-model").strip()
        self.image_model: str = os.environ.get("IMAGE_MODEL", "private-image-model").strip()
        
        self.request_timeout_seconds: float = float(os.environ.get("REQUEST_TIMEOUT_SECONDS", "120"))
        self.max_request_bytes: int = int(os.environ.get("MAX_REQUEST_BYTES", str(10 * 1024 * 1024)))  # 10MB default

settings = Settings()

def reload_settings() -> Settings:
    global settings
    settings = Settings()
    return settings
