"""
Function-calling tools for the HR chatbot.
These are registered with Gemini via bind_tools() and executed inside handle_tool_calls_node.
position_id is injected at runtime from HRChatState and not exposed in the tool schema.
"""

from langchain_core.tools import tool
from typing import Optional
from app.services.recruitment_api import recruitment_api


@tool
async def get_candidate_details(candidate_id: str, position_id: int) -> str:
    """
    Lấy thông tin chi tiết score/feedback của một ứng viên cụ thể từ database.
    Gọi tool này khi HR hỏi về điểm số, nhận xét, hoặc kỹ năng của một ứng viên.

    Args:
        candidate_id: UUID của ứng viên
        position_id: ID của vị trí ứng tuyển (từ context phiên làm việc)
    """
    try:
        details = await recruitment_api.get_candidate_details(
            candidate_id=candidate_id,
            position_id=position_id
        )
        if not details:
            return f"Không tìm thấy thông tin ứng viên {candidate_id} cho vị trí {position_id}."

        score = details.get("score", "N/A")
        feedback = details.get("feedback", "Không có nhận xét.")
        app_cv_id = details.get("appCvId", "N/A")
        return (
            f"Ứng viên {candidate_id} — Điểm: {score}/100\n"
            f"Nhận xét: {feedback}\n"
            f"Application CV ID: {app_cv_id}"
        )
    except Exception as e:
        return f"Lỗi khi lấy thông tin ứng viên: {str(e)}"


@tool
async def send_interview_email(
    candidate_id: str,
    candidate_email: str,
    candidate_name: str,
    position_id: int,
    position_name: str,
    email_type: str,
    custom_message: str,
    interview_date: Optional[str] = None
) -> str:
    """
    Gửi email thông báo cho ứng viên qua SMTP.
    Hỗ trợ 3 loại: INTERVIEW_INVITE, OFFER_LETTER, REJECTION.

    Args:
        candidate_id: UUID của ứng viên
        candidate_email: Địa chỉ email của ứng viên
        candidate_name: Tên đầy đủ của ứng viên
        position_id: ID vị trí ứng tuyển
        position_name: Tên vị trí ứng tuyển (hiển thị trong email)
        email_type: Loại email — INTERVIEW_INVITE | OFFER_LETTER | REJECTION
        custom_message: Nội dung tùy chỉnh thêm vào email
        interview_date: Ngày phỏng vấn theo định dạng ISO (chỉ dùng cho INTERVIEW_INVITE)
    """
    valid_types = {"INTERVIEW_INVITE", "OFFER_LETTER", "REJECTION"}
    if email_type.upper() not in valid_types:
        return f"Loại email không hợp lệ: {email_type}. Chọn một trong: {valid_types}"

    try:
        await recruitment_api.send_interview_email(
            candidate_id=candidate_id,
            candidate_email=candidate_email,
            candidate_name=candidate_name,
            position_id=position_id,
            position_name=position_name,
            email_type=email_type.upper(),
            interview_date=interview_date,
            custom_message=custom_message
        )
        type_label = {
            "INTERVIEW_INVITE": "mời phỏng vấn",
            "OFFER_LETTER": "offer letter",
            "REJECTION": "từ chối"
        }.get(email_type.upper(), email_type)
        return f"Đã gửi email {type_label} thành công tới {candidate_name} ({candidate_email})."
    except Exception as e:
        return f"Lỗi khi gửi email: {str(e)}"


# Registered tool list — order matters for bind_tools()
HR_TOOLS = [get_candidate_details, send_interview_email]
