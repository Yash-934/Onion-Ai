import hashlib
import json
import os
import secrets
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple
import config

def hash_secret(secret: str) -> str:
    """
    Returns SHA-256 hex digest of the secret.
    """
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()

class KeyStore:
    def __init__(self):
        self._keys: Dict[str, Dict[str, Any]] = {}
        self._load()

    def _load(self):
        file_path = config.settings.keys_file
        if os.path.exists(file_path):
            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    self._keys = json.load(f)
            except Exception:
                self._keys = {}

    def _save(self):
        file_path = config.settings.keys_file
        try:
            os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                json.dump(self._keys, f, indent=2)
        except Exception:
            pass

    def create_key(
        self,
        name: str = "",
        expires_in_days: Optional[int] = None,
        rate_limit: Optional[int] = None
    ) -> Tuple[Dict[str, Any], str]:
        raw_secret = f"sk-priv-{secrets.token_urlsafe(32)}"
        key_id = f"key_{secrets.token_hex(8)}"
        secret_hash = hash_secret(raw_secret)
        now_iso = datetime.now(timezone.utc).isoformat()
        
        expires_at = None
        if expires_in_days and expires_in_days > 0:
            expires_at = datetime.fromtimestamp(time.time() + expires_in_days * 86400, timezone.utc).isoformat()

        key_data = {
            "id": key_id,
            "name": name or f"Key {key_id[:10]}",
            "key_hash": secret_hash,
            "created_at": now_iso,
            "expires_at": expires_at,
            "enabled": True,
            "rate_limit": rate_limit if rate_limit and rate_limit > 0 else config.settings.rate_limit_per_minute,
            "last_used_at": None,
            "request_timestamps": []
        }
        self._keys[key_id] = key_data
        self._save()

        # Metadata for user (excluding hash)
        metadata = {
            "id": key_id,
            "name": key_data["name"],
            "created_at": key_data["created_at"],
            "expires_at": key_data["expires_at"],
            "enabled": True,
            "rate_limit": key_data["rate_limit"],
            "last_used_at": None
        }
        return metadata, raw_secret

    def list_keys(self) -> List[Dict[str, Any]]:
        self._load()
        result = []
        for k_id, k_data in self._keys.items():
            result.append({
                "id": k_id,
                "name": k_data.get("name", ""),
                "created_at": k_data.get("created_at"),
                "expires_at": k_data.get("expires_at"),
                "enabled": k_data.get("enabled", True),
                "rate_limit": k_data.get("rate_limit", config.settings.rate_limit_per_minute),
                "last_used_at": k_data.get("last_used_at")
            })
        return result

    def revoke_key(self, key_id: str) -> bool:
        self._load()
        if key_id in self._keys:
            self._keys[key_id]["enabled"] = False
            self._save()
            return True
        return False

    def rotate_key(self, key_id: str) -> Optional[Tuple[Dict[str, Any], str]]:
        self._load()
        if key_id not in self._keys:
            return None
        new_secret = f"sk-priv-{secrets.token_urlsafe(32)}"
        new_hash = hash_secret(new_secret)
        self._keys[key_id]["key_hash"] = new_hash
        self._keys[key_id]["enabled"] = True
        self._save()

        metadata = {
            "id": key_id,
            "name": self._keys[key_id].get("name", ""),
            "created_at": self._keys[key_id].get("created_at"),
            "expires_at": self._keys[key_id].get("expires_at"),
            "enabled": True,
            "rate_limit": self._keys[key_id].get("rate_limit", config.settings.rate_limit_per_minute),
            "last_used_at": self._keys[key_id].get("last_used_at")
        }
        return metadata, new_secret

    def check_key(self, raw_key: str) -> Tuple[bool, str, int]:
        """
        Validates raw_key against registered API keys.
        Checks hash, enabled state, expiration, and rate limits.
        """
        self._load()
        target_hash = hash_secret(raw_key)

        for key_id, key_data in self._keys.items():
            stored_hash = key_data.get("key_hash", "")
            if secrets.compare_digest(target_hash, stored_hash):
                if not key_data.get("enabled", True):
                    return False, "API key has been revoked or disabled.", 403

                # Expiration check
                expires_at = key_data.get("expires_at")
                if expires_at:
                    try:
                        exp_dt = datetime.fromisoformat(expires_at)
                        if datetime.now(timezone.utc) > exp_dt:
                            return False, "API key has expired.", 401
                    except Exception:
                        pass

                # Rate limit check (sliding 60s window)
                rate_limit = key_data.get("rate_limit", config.settings.rate_limit_per_minute)
                now = time.time()
                timestamps = [t for t in key_data.get("request_timestamps", []) if now - t < 60.0]
                if len(timestamps) >= rate_limit:
                    return False, f"Rate limit of {rate_limit} requests/minute exceeded.", 429

                timestamps.append(now)
                key_data["request_timestamps"] = timestamps
                key_data["last_used_at"] = datetime.now(timezone.utc).isoformat()
                self._save()
                return True, "", 200

        return False, "Invalid API key provided.", 401

_keystore = KeyStore()

def get_keystore() -> KeyStore:
    return _keystore

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

def is_admin_authorized(authorization: Optional[str] = None, x_api_key: Optional[str] = None) -> Tuple[bool, str]:
    """
    Validates gateway admin authentication.
    """
    admin_key = config.settings.gateway_admin_key
    if not admin_key:
        return False, "Admin authentication key is not configured on the gateway."

    provided = extract_api_key(authorization, x_api_key)
    if not provided:
        return False, "Missing Admin API key. Provide Authorization: Bearer <admin_key> or x-api-key."

    if secrets.compare_digest(provided, admin_key):
        return True, ""
    return False, "Invalid Admin credentials."

def is_authorized(authorization: Optional[str] = None, x_api_key: Optional[str] = None) -> Tuple[bool, str]:
    """
    Validates the gateway API key.
    Checks master GATEWAY_API_KEY as well as any generated client keys in keystore.
    Returns (True, "") if authorized, or (False, "error message") if unauthorized.
    """
    expected_master = config.settings.gateway_api_key
    provided = extract_api_key(authorization, x_api_key)

    # If neither master key nor generated keys exist, open access
    if not expected_master and not _keystore.list_keys():
        return True, ""

    if not provided:
        return False, "Missing API key. Please provide 'x-api-key' or 'Authorization: Bearer <key>' header."

    # 1. Master key constant-time comparison
    if expected_master and secrets.compare_digest(provided, expected_master):
        return True, ""

    # 2. Check keystore generated keys
    ok, err_msg, status_code = _keystore.check_key(provided)
    if ok:
        return True, ""

    return False, err_msg
