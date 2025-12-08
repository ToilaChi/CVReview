import httpx
from typing import Optional, Dict, Any
from app.config import get_settings

settings = get_settings()

class HTTPClient:
    """HTTP client for calling external services"""
    
    def __init__(self, base_url: str = "http://localhost:8082", timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.client = httpx.AsyncClient(timeout=timeout)
        self.auth_token: Optional[str] = None

    async def close(self):
        """Close HTTP client"""
        await self.client.aclose()

# Global instance
http_client = HTTPClient()