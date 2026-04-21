1. Candidate chatbot:
- Trả lời ngáo, không đúng trọng tâm (chỉ trả lời được đúng trọng tâm 1 2 loại câu hỏi liên quan đến phân tích CV match JD không, or câu hỏi apply, or điểm số), dính markdown (# ##) và /n. 
- Trả lời chậm hơn Candidate mode và HR mode (lần đầu chậm thì chập nhận được vì phả scoring nữa).
- Cung cấp đủ field "nhạy cảm" mới apply được
{
  "session_id": "7c45e84c-b506-4ea0-b308-0559947bca61",
  "query": "That's great, please help me apply for the Fresher Java Developer position based on that analysis. Candidate Id: 270e934c-1ef7-11f1-9dd4-423ac0aaa300, Session Id:7c45e84c-b506-4ea0-b308-0559947bca61, position Id: 2",
  "candidate_id": "270e934c-1ef7-11f1-9dd4-423ac0aaa300"
}

{
    "answer": "I have successfully submitted your application for the **Developer Java Frehser** position. Please wait for our HR response!",
    "metadata": {
        "intent": "jd_search",
        "intent_confidence": 0.6,
        "domain": "career",
        "cv_chunks_used": 6,
        "jd_docs_used": 3,
        "temperature_used": 0.3,
        "function_calls": [
            {
                "name": "finalize_application",
                "arguments": {
                    "position_id": 2,
                    "skill_miss": "",
                    "feedback": "This role presents an excellent opportunity, as your foundational Java and Spring Boot skills align perfectly with the core requirements, including demonstrated project experience. No critical skill gaps were identified for this Fresher position, indicating a strong baseline for success.",
                    "session_id": "ba5ad295-6b83-4d2a-b9fe-cff2e2398994",
                    "skill_match": "Java Core (OOP, Collections), Basic Spring Boot (REST, JPA), SQL, Git, Project Experience",
                    "score": 85,
                    "candidate_id": "270e915d-1ef7-11f1-9dd4-423ac0aaa300"
                },
                "result": "Nộp đơn thành công. Application CV ID: 4"
            }
        ],
        "scored_jobs": [
            {
                "score": 0,
                "feedback": "The candidate's intern-level experience and skill set are a significant mismatch for this mid-level position.",
                "skillMiss": [
                    "Spring Security (JWT/OAuth2)",
                    "Spring Batch",
                    "Redis",
                    "Advanced Testing (Mockito, TestContainers)",
                    "Git Flow",
                    "Docker",
                    "Concurrent programming"
                ],
                "positionId": 1,
                "skillMatch": [
                    "Java",
                    "Spring Boot",
                    "SQL optimization",
                    "Microservices"
                ],
                "missedCount": 7,
                "matchedCount": 4
            },
            {
                "score": 85,
                "feedback": "This candidate is a strong fit for the Fresher role, meeting all core requirements and only missing some nice-to-haves.",
                "skillMiss": [],
                "positionId": 2,
                "skillMatch": [
                    "Java Core (OOP, Collections)",
                    "Basic Spring Boot (REST, JPA)",
                    "SQL",
                    "Git",
                    "Project Experience"
                ],
                "missedCount": 0,
                "matchedCount": 5
            },
            {
                "score": 45,
                "feedback": "The candidate meets the experience level but lacks several specific required skills for this internship.",
                "skillMiss": [
                    "basic Multithreading",
                    "basic Security",
                    "Git (branching strategy, PR workflow)"
                ],
                "positionId": 3,
                "skillMatch": [
                    "Java Core (OOP, Collections)",
                    "Spring Boot (REST, JPA)",
                    "SQL"
                ],
                "missedCount": 3,
                "matchedCount": 3
            }
        ]
    }
}

2. HR chatbot
# HR chatbot: Candidate mode
- Trả lời tạm được, ngắn gọn không dài dòng, không bị dính markdown

- Send mail phải cung cấp đủ thông tin
{
  "session_id": "967360d8-5855-47a0-a54e-8396517dd291",
  "hr_id": "a2d53c24-2bf6-11f1-9dd4-423ac0aaa300",
  "position_id": 2,
  "mode": "CANDIDATE_MODE",
  "query": "Send an interview invitation email to Pham Minh Chi on April 30th at 9:00 AM. Chi's email: chi12345pham@gmail.com, position name: Fresher Java Developer"
}

# HR chatbot: HR mode
- Trả lời cũng tạm, ngắn gọn, không bị dính markdown

- Send email sao lại phải cần đến Candidate ID? Candidate ID chỉ cần cho Candidate mode thôi vì CV trong HR mode là do HR upload sao mà có candidateID được.


=> Điểm chung là đều phải cung cấp thông tin "nhạy cảm" mới apply được, điều lẽ ra hệ thống tự phải get. 