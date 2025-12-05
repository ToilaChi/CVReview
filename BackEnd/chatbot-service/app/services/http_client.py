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
    
    def set_auth_token(self, token: str):
        """Set authorization token for requests"""
        self.auth_token = token
    
    def _get_headers(self) -> Dict[str, str]:
        """Get request headers with authorization if available"""
        headers = {"Content-Type": "application/json"}
        if self.auth_token:
            headers["Authorization"] = f"Bearer {self.auth_token}"
        return headers
    
    async def get_cv_chunks(self, cv_id: int) -> Optional[Dict[str, Any]]:
        """
        Get CV chunks from chunking service
        
        Args:
            cv_id: CV ID
            
        Returns:
            CV chunks response or None if failed
        """
        try:
            url = f"{self.base_url}/chunking/{cv_id}"
            print(f"Fetching CV chunks from: {url}")
            
            response = await self.client.get(url, headers=self._get_headers())
            response.raise_for_status()
            
            data = response.json()
            print(f"Fetched {data.get('totalChunks', 0)} chunks for CV {cv_id}")
            return data
            
        except httpx.HTTPStatusError as e:
            print(f"HTTP error fetching CV chunks: {e.response.status_code} - {e.response.text}")
            return None
        except Exception as e:
            print(f"Error fetching CV chunks: {e}")
            return None
    
    async def get_jd_text(self, position_id: int) -> Optional[str]:
        """
        Get JD text from recruitment service
        
        Args:
            position_id: Position/JD ID
            
        Returns:
            JD text or None if failed
        """
        try:
            url = f"{self.base_url}/positions/jd/{position_id}/text"
            print(f"Fetching JD text from: {url}")
            
            response = await self.client.get(url, headers=self._get_headers())
            response.raise_for_status()
            
            data = response.json()
            # Extract jdText from nested data structure
            jd_data = data.get("data", {})
            jd_text = jd_data.get("jdText", "") if isinstance(jd_data, dict) else ""
            print(f"Fetched JD text for position {position_id} ({len(jd_text)} chars)")
            return jd_text
            
        except httpx.HTTPStatusError as e:
            print(f"HTTP error fetching JD text: {e.response.status_code} - {e.response.text}")
            return None
        except Exception as e:
            print(f"Error fetching JD text: {e}")
            return None
    
    async def close(self):
        """Close HTTP client"""
        await self.client.aclose()


# Global instance
http_client = HTTPClient()