from langchain_core.tools import tool
from typing import List, Optional
from app.services.recruitment_api import recruitment_api
from app.config import get_settings

settings = get_settings()

@tool
async def evaluate_cv_fit(position_ids: List[int]) -> str:
    """
    Tính điểm phù hợp của CV với một hoặc nhiều JDs. Gọi khi user hỏi về độ phù hợp hoặc tìm việc.
    
    Args:
        position_ids: Danh sách positionId cần chấm điểm (tối đa 10)
    """
    # This tool is just a marker for LangGraph to know what to do.
    # The actual scoring is usually handled by a dedicated node or just returning an instruction.
    return "Đang phân tích độ phù hợp của bạn với các vị trí. Vui lòng chờ..."

@tool
async def finalize_application(position_id: int, score: int, feedback: str, skill_match: str, skill_miss: str, candidate_id: str, session_id: str) -> str:
    """
    Nộp đơn ứng tuyển chính thức. CHỈ được gọi khi score >= 70.
    
    Args:
        position_id: ID của vị trí ứng tuyển
        score: Điểm phù hợp (từ evaluate_cv_fit)
        feedback: Nhận xét chung
        skill_match: Các kỹ năng phù hợp
        skill_miss: Các kỹ năng còn thiếu
    """
    if score < settings.SCORE_THRESHOLD:
        return f"Không thể nộp đơn do điểm phù hợp ({score}) dưới mức tối thiểu là {settings.SCORE_THRESHOLD}. Vui lòng phân tích thêm các kỹ năng còn thiếu."
        
    try:
        res = await recruitment_api.finalize_application(
            candidate_id=candidate_id,
            position_id=position_id,
            score=score,
            feedback=feedback,
            skill_match=skill_match,
            skill_miss=skill_miss,
            session_id=session_id
        )
        return f"Nộp đơn thành công. Application CV ID: {res.get('applicationCvId')}"
    except Exception as e:
        return f"Lỗi khi nộp đơn: {str(e)}"

CANDIDATE_TOOLS = [evaluate_cv_fit, finalize_application]
