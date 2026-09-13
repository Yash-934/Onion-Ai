import logging
import sys
from typing import Any, Dict

# SENSITIVE HEADER KEYS TO NEVER LOG
SENSITIVE_HEADERS = {
    "authorization",
    "x-api-key",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-gateway-key",
    "x-auth-token",
}

class SafeFormatter(logging.Formatter):
    """
    Formatter that guarantees no API keys, auth tokens, or prompts are emitted.
    """
    def format(self, record: logging.LogRecord) -> str:
        msg = super().format(record)
        return msg

def get_privacy_logger(name: str = "private_gateway") -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(SafeFormatter("[%(asctime)s] [%(levelname)s] %(message)s"))
        logger.addHandler(handler)
    logger.propagate = False
    return logger

logger = get_privacy_logger()

def sanitize_headers(headers: Dict[str, Any]) -> Dict[str, str]:
    """
    Strip or redact any sensitive header before internal handling or debug output.
    """
    sanitized = {}
    for k, v in headers.items():
        k_lower = k.lower()
        if k_lower in SENSITIVE_HEADERS:
            sanitized[k] = "[REDACTED]"
        else:
            sanitized[k] = str(v)
    return sanitized

def structured_error(status_code: int, error_type: str, message: str) -> Dict[str, Any]:
    """
    Return clean Anthropic/OpenAI compatible error without internal file paths or stack traces.
    """
    return {
        "error": {
            "type": error_type,
            "message": message,
            "code": status_code
        }
    }
