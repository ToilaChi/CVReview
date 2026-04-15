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
            res = response.json()
            return res.get("data") or {}
            
    async def get_history(self, session_id: str, limit: int = 20) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/session/{session_id}/history", params={"limit": limit}, headers=self.headers)
            response.raise_for_status()
            res = response.json()
            return res.get("data") or []
            
    async def save_message(self, session_id: str, role: str, content: str, function_call: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        async with httpx.AsyncClient() as client:
            payload = {"sessionId": session_id, "role": role, "content": content}
            if function_call:
                payload["functionCall"] = function_call
            response = await client.post(f"{self.base_url}/internal/chatbot/message", json=payload, headers=self.headers)
            response.raise_for_status()
            res = response.json()
            return res.get("data") or {}
            
    async def get_active_positions(self) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/positions/active", headers=self.headers)
            response.raise_for_status()
            res = response.json()
            return res.get("data") or []
            
    async def get_applications(self, position_id: int) -> List[Dict[str, Any]]:
        async with httpx.AsyncClient() as client:
            response = await client.get(f"{self.base_url}/internal/chatbot/applications", params={"positionId": position_id}, headers=self.headers)
            response.raise_for_status()
            res = response.json()
            return res.get("data") or []
            
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
            res = response.json()
            return res.get("data") or {}

    async def get_candidate_details(self, candidate_id: str, position_id: int) -> Dict[str, Any]:
        """Fetch score and feedback for a specific candidate application from SQL."""
        async with httpx.AsyncClient() as client:
            response = await client.get(
                f"{self.base_url}/internal/chatbot/applications",
                params={"positionId": position_id, "candidateId": candidate_id},
                headers=self.headers
            )
            response.raise_for_status()
            res = response.json()
            results = res.get("data") or []
            # API returns a list — extract the matching candidate's record
            for item in results:
                if item.get("candidateId") == candidate_id:
                    return item
            return {}

    async def send_interview_email(
        self,
        candidate_id: str,
        candidate_email: str,
        candidate_name: str,
        position_id: int,
        position_name: str,
        email_type: str,
        interview_date: Optional[str],
        custom_message: str
    ) -> Dict[str, Any]:
        """Trigger SMTP email via recruitment-service notification endpoint."""
        async with httpx.AsyncClient() as client:
            payload = {
                "candidateId": candidate_id,
                "candidateEmail": candidate_email,
                "candidateName": candidate_name,
                "positionId": position_id,
                "positionName": position_name,
                "emailType": email_type,
                "customMessage": custom_message
            }
            if interview_date:
                payload["interviewDate"] = interview_date
            response = await client.post(
                f"{self.base_url}/internal/chatbot/notify/interview",
                json=payload,
                headers=self.headers
            )
            response.raise_for_status()
            res = response.json()
            return res.get("data") or {}


recruitment_api = RecruitmentAPI()
