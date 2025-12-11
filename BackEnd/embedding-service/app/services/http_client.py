import httpx
from typing import Optional, Dict, Any
from app.config import get_settings

settings = get_settings()

class HTTPClient:
    """HTTP client for calling external services"""
    
    def __init__(self, timeout: float = 30.0):
        self.timeout = timeout
        self.client = httpx.AsyncClient(timeout=timeout)

    async def close(self):
        """Close HTTP client"""
        await self.client.aclose()

# Global instance
http_client = HTTPClient()