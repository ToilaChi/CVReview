import httpx
from typing import Dict, Any, List, Optional
from app.config import get_settings

settings = get_settings()

class RecruitmentAPI:
    def __init__(self):
        self.base_url = settings.RECRUITMENT_SERVICE_URL
        self.headers = {
            "X-Internal-Service": settings.INTERNAL_SERVICE_SECRET,
            "Content-Type": "application/json"
        }
        
    async def create_session(self, user_id: str, chatbot_type: str, position_id: Optional[int] = None, mode: Optional[str] = None) -> Dict[str, Any]:
        async with httpx.AsyncClient() as client:
            payload = {"userId": user_id, "chatbotType": chatbot_type}
            if position_id:
                payload["positionId"] = position_id
            if mode:
                payload["mode"] = mode
            response = await client.post(f"{self.base_url}/internal/chatbot/session", json=payload, headers=self.headers)
            response.raise_for_status()
            return response.json()
            
    async def get_history(self, session_id: str, limit: int = 20) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/session/{session_id}/history", params={"limit": limit}, headers=self.headers)
            response.raise_for_status()
            return response.json()
            
    async def save_message(self, session_id: str, role: str, content: str, function_call: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        async with httpx.AsyncClient() as client:
            payload = {"sessionId": session_id, "role": role, "content": content}
            if function_call:
                payload["functionCall"] = function_call
            response = await client.post(f"{self.base_url}/internal/chatbot/message", json=payload, headers=self.headers)
            response.raise_for_status()
            return response.json()
            
    async def get_active_positions(self) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/positions/active", headers=self.headers)
            response.raise_for_status()
            return response.json()
            
    async def get_applications(self, position_id: int) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/applications", params={"positionId": position_id}, headers=self.headers)
            response.raise_for_status()
            return response.json()
            
    async def finalize_application(self, candidate_id: str, position_id: int, score: int, feedback: str, skill_match: str, skill_miss: str, session_id: str) -> Dict[str, Any]:
        async with httpx.AsyncClient() as client:
            payload = {
                "candidateId": candidate_id,
                "positionId": position_id,
                "score": score,
                "feedback": feedback,
                "skillMatch": skill_match,
                "skillMiss": skill_miss,
                "sessionId": session_id
            }
            response = await client.post(f"{self.base_url}/internal/chatbot/finalize-application", json=payload, headers=self.headers)
            response.raise_for_status()
            return response.json()

recruitment_api = RecruitmentAPI()
