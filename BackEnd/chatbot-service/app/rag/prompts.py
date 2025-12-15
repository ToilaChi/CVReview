"""
Domain-specific prompts for career counseling chatbot
Optimized for CV/JD analysis and matching
"""

# ============================================================
# SYSTEM PROMPTS
# ============================================================

SYSTEM_PROMPT = """You are an expert Career Counselor Assistant specializing in job matching.

Your expertise:
- Matching candidate CVs to suitable job positions
- Analyzing job descriptions and requirements
- Identifying skill gaps and providing learning roadmap
- Suggesting career development paths

Response guidelines:
✓ Professional, encouraging, and actionable
✓ Data-driven based on provided context
✓ Specific recommendations with examples
✓ Honest about gaps but focus on solutions

CRITICAL RULES:
1. ONLY use information from provided CONTEXT
2. State clearly if information is unavailable
3. NEVER hallucinate CV or JD details
4. Always cite which JD/CV section you reference
5. For skill gaps, provide concrete learning suggestions
"""


# ============================================================
# INTENT-SPECIFIC PROMPTS
# ============================================================

JD_ANALYSIS_PROMPT = """You are analyzing a specific job position.

JOB DESCRIPTION:
{jd_context}

CANDIDATE PROFILE (for gap analysis):
{cv_context}

USER QUESTION:
{query}

Provide comprehensive job analysis:

1. **Position Overview**: Role title, level, department
2. **Key Requirements**: 
   - Must-have skills
   - Nice-to-have skills
   - Experience level needed
3. **Responsibilities**: Main duties and tasks
4. **Match Assessment** (if CV provided):
   - Matching strengths
   - Skill gaps
   - Recommendation: Apply / Needs Preparation / Not Ready
5. **Additional Info**: Salary, benefits, culture (if mentioned)

Be detailed and actionable.

Your analysis:"""


JD_SEARCH_PROMPT = """You are helping match a candidate to suitable positions.

CANDIDATE PROFILE (CV):
{cv_context}

AVAILABLE JOB POSITIONS:
{jd_context}

USER QUESTION:
{query}

Analyze and respond based on the question type:

**If asking for job recommendations:**
- Rank positions by match score (High/Medium/Low)
- Explain WHY each position fits
- Highlight matching skills from CV
- Note any skill gaps

**If asking about skill gaps or learning needs:**
- Compare CV skills against JD requirements
- Identify missing technical skills
- Identify missing soft skills or experience
- Provide specific learning recommendations:
  * Online courses (Udemy, Coursera, etc.)
  * Certifications to pursue
  * Projects to build
  * Estimated timeline for each
- Prioritize by importance

Format clearly with sections and bullet points.

Your response:"""


GENERAL_PROMPT = """You are a helpful job matching assistant.

CONTEXT:
{context}

USER QUESTION:
{query}

Provide helpful guidance. If you need more information (CV or specific JD), ask politely.

Your response:"""


# ============================================================
# CONTEXT BUILDERS
# ============================================================

def build_cv_context(cv_chunks: list) -> str:
    """
    Build formatted CV context from retrieved chunks
    
    Args:
        cv_chunks: List of dicts with 'payload' containing CV data
        
    Returns:
        Formatted string ready for prompt
    """
    if not cv_chunks:
        return "No CV information available."
    
    context_parts = []
    
    for i, chunk in enumerate(cv_chunks, 1):
        payload = chunk.get("payload", {})
        section = payload.get("section", "Unknown")
        text = payload.get("chunkText", "")
        score = chunk.get("score", 0)
        
        # Clean text
        text = text.strip()
        if not text:
            continue
        
        # Format
        context_parts.append(
            f"--- CV Section {i}: {section} (Relevance: {score:.2f}) ---\n{text}\n"
        )
    
    return "\n".join(context_parts)


def build_jd_context(jd_docs: list) -> str:
    """
    Build formatted JD context from retrieved documents
    
    Args:
        jd_docs: List of dicts with 'payload' containing JD data
        
    Returns:
        Formatted string ready for prompt
    """
    if not jd_docs:
        return "No job descriptions available."
    
    context_parts = []
    
    for i, doc in enumerate(jd_docs, 1):
        payload = doc.get("payload", {})
        position = payload.get("position", "Unknown Position")
        jd_text = payload.get("jdText", "")
        score = doc.get("score", 0)
        
        # Truncate long JD texts (keep first 1000 chars)
        if len(jd_text) > 1000:
            jd_text = jd_text[:1000] + "...\n[Content truncated for brevity]"
        
        context_parts.append(
            f"--- Job Position {i}: {position} (Match Score: {score:.2f}) ---\n{jd_text}\n"
        )
    
    return "\n".join(context_parts)


def build_combined_context(cv_chunks: list, jd_docs: list) -> str:
    """Build context with both CV and JD"""
    cv_ctx = build_cv_context(cv_chunks)
    jd_ctx = build_jd_context(jd_docs)
    
    return f"""=== CANDIDATE PROFILE ===
{cv_ctx}

=== JOB REQUIREMENTS ===
{jd_ctx}
"""


# ============================================================
# PROMPT SELECTOR
# ============================================================

def get_prompt_for_intent(
    intent: str,
    query: str,
    cv_context: list = None,
    jd_context: list = None,
    conversation_history: list = None
) -> tuple[str, str]:
    """
    Get appropriate prompt based on intent
    
    Args:
        intent: Classified intent
        query: User query
        cv_context: Retrieved CV chunks
        jd_context: Retrieved JD documents
        
    Returns:
        Tuple of (system_prompt, user_prompt)
    """
    cv_context = cv_context or []
    jd_context = jd_context or []
    
    # Build context với conversation history nếu có
    cv_ctx = build_cv_context(cv_context)
    jd_ctx = build_jd_context(jd_context)
    
    # Thêm conversation history vào user_prompt nếu có
    history_text = ""
    if conversation_history:
        history_text = "\n\nPREVIOUS CONVERSATION:\n" + "\n".join([
            f"User: {turn['query']}\nAssistant: {turn['answer'][:200]}..."
            for turn in conversation_history[-3:]  # Giữ 3 turn gần nhất
        ]) + "\n"
    
    if intent == "jd_search":
        user_prompt = JD_SEARCH_PROMPT.format(
            cv_context=cv_ctx,
            jd_context=jd_ctx,
            query=query
        ) + history_text
    
    elif intent == "jd_analysis":
        user_prompt = JD_ANALYSIS_PROMPT.format(
            jd_context=jd_ctx,
            cv_context=cv_ctx,  # Để gap analysis
            query=query
        ) + history_text
    
    else:  # general
        combined = build_combined_context(cv_context, jd_context)
        user_prompt = GENERAL_PROMPT.format(
            context=combined if (cv_context or jd_context) else "None",
            query=query
        )
    
    return SYSTEM_PROMPT, user_prompt


# ============================================================
# TESTING
# ============================================================

if __name__ == "__main__":
    # Test prompt building
    mock_cv_chunk = {
        "score": 0.85,
        "payload": {
            "section": "EXPERIENCE",
            "chunkText": "Senior Python Developer at Tech Corp\n- Built REST APIs using FastAPI\n- 5 years experience"
        }
    }
    
    mock_jd = {
        "score": 0.78,
        "payload": {
            "position": "Backend Engineer",
            "jdText": "We need a Python developer with FastAPI experience..."
        }
    }
    
    system, user = get_prompt_for_intent(
        intent="cv_jd_match",
        query="Am I qualified for this position?",
        cv_context=[mock_cv_chunk],
        jd_context=[mock_jd]
    )
    
    print("SYSTEM PROMPT:")
    print(system)
    print("\n" + "="*80 + "\n")
    print("USER PROMPT:")
    print(user)