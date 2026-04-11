package org.example.recruitmentservice.models.enums;

/**
 * Mode hoạt động của HR chatbot.
 * HR_MODE: làm việc với CVs do HR upload (sourceType=HR).
 * CANDIDATE_MODE: làm việc với CVs do Candidate nộp vào (sourceType=CANDIDATE).
 */
public enum ChatMode {
    HR_MODE,
    CANDIDATE_MODE
}
