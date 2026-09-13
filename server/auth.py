import secrets
from typing import Optional, Tuple
import config

def extract_api_key(authorization: Optional[str] = None, x_api_key: Optional[str] = None) -> Optional[str]:
    """
    Extracts the API key from either x-api-key or Authorization: Bearer header.
    """
    if x_api_key and x_api_key.strip():
        return x_api_key.strip()
    
    if authorization and authorization.strip():
        parts = authorization.strip().split()
        if len(parts) == 2 and parts[0].lower() == "bearer":
            return parts[1]
        elif len(parts) == 1:
            return parts[0]
    return None

def is_authorized(authorization: Optional[str] = None, x_api_key: Optional[str] = None) -> Tuple[bool, str]:
    """
    Validates the gateway API key.
    Returns (True, "") if authorized, or (False, "error message") if unauthorized.
    """
    expected = config.settings.gateway_api_key
    if not expected:
        # If no gateway key is set on the server, allow all requests
        return True, ""
    
    provided = extract_api_key(authorization, x_api_key)
    if not provided:
        return False, "Missing API key. Please provide 'x-api-key' or 'Authorization: Bearer <key>' header."
    
    # Constant-time comparison to prevent timing attacks
    if secrets.compare_digest(provided, expected):
        return True, ""
    
    return False, "Invalid API key provided."
