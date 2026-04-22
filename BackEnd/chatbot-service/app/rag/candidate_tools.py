from langchain_core.tools import tool
from typing import List, Optional
from app.services.recruitment_api import recruitment_api
from app.config import get_settings

settings = get_settings()


@tool
async def evaluate_cv_fit(position_ids: List[int]) -> str:
    """
    Tính điểm phù hợp của CV với một hoặc nhiều JDs.
    Gọi khi user hỏi về độ phù hợp hoặc tìm việc.

    Args:
        position_ids: Danh sách positionId cần chấm điểm (tối đa 10)
    """
    # Scoring is handled by the dedicated scoring_node — this tool marker
    # allows the LLM to signal intent when the cache is cold.
    return "Đang phân tích độ phù hợp của bạn với các vị trí. Vui lòng chờ..."


@tool
async def finalize_application(
    position_id: int,
    score: int,
    feedback: str,
    skill_match: str,
    skill_miss: str,
    candidate_id: str,
    session_id: str,
) -> str:
    """
    Nộp đơn ứng tuyển chính thức. CHỈ được gọi khi score >= 70.

    Args:
        position_id: ID của vị trí ứng tuyển
        score: Điểm phù hợp (phải >= 70 từ evaluate_cv_fit)
        feedback: Nhận xét chung từ quá trình đánh giá
        skill_match: Các kỹ năng phù hợp với yêu cầu vị trí
        skill_miss: Các kỹ năng còn thiếu so với yêu cầu vị trí
        candidate_id: UUID của ứng viên (tự động inject từ session)
        session_id: ID phiên hội thoại (tự động inject từ session)
    """
    if score < settings.SCORE_THRESHOLD:
        return (
            f"Không thể nộp đơn do điểm phù hợp ({score}) dưới mức tối thiểu "
            f"là {settings.SCORE_THRESHOLD}. Vui lòng phân tích thêm các kỹ năng còn thiếu."
        )

    try:
        res = await recruitment_api.finalize_application(
            candidate_id=candidate_id,
            position_id=position_id,
            score=score,
            feedback=feedback,
            skill_match=skill_match,
            skill_miss=skill_miss,
            session_id=session_id,
        )
        return f"Nộp đơn thành công. Application CV ID: {res.get('applicationCvId')}"
    except Exception as e:
        return f"Lỗi khi nộp đơn: {str(e)}"


@tool
async def check_application_status(
    candidate_id: str,
    position_id: Optional[int] = None,
) -> str:
    """
    Kiểm tra trạng thái ứng tuyển của ứng viên — đã nộp đơn vị trí nào, điểm bao nhiêu.
    Gọi khi candidate hỏi "Tôi đã apply chưa?", "Đơn của tôi thế nào?", "Tôi đã nộp vị trí nào?".

    Args:
        candidate_id: UUID của ứng viên (tự động inject từ session)
        position_id:  ID vị trí muốn kiểm tra cụ thể (optional — None = lấy tất cả)
    """
    try:
        data = await recruitment_api.check_application_status(
            candidate_id=candidate_id,
            position_id=position_id,
        )
        applications: list = data.get("applications", [])

        if not applications:
            if position_id:
                return f"Bạn chưa nộp đơn vào vị trí ID {position_id}."
            return "Bạn chưa nộp đơn vào vị trí nào."

        lines = ["Trạng thái ứng tuyển của bạn:"]
        for app in applications:
            pos_name = app.get("positionName", f"Position #{app.get('positionId')}")
            score    = app.get("score", "Chưa chấm")
            status   = app.get("status", "Đã nộp")
            lines.append(f"• {pos_name} — Điểm: {score} | Trạng thái: {status}")

        return "\n".join(lines)
    except Exception as e:
        return f"Lỗi khi kiểm tra trạng thái ứng tuyển: {str(e)}"


CANDIDATE_TOOLS = [evaluate_cv_fit, finalize_application, check_application_status]
