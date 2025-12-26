"""
- Forced structured output with exact format requirements
- More specific instructions per intent
- Better handling of edge cases (missing info)
- Clearer section headers and formatting rules
"""

# ============================================================
# SYSTEM PROMPT (Enhanced)
# ============================================================

SYSTEM_PROMPT = """You are an expert Career Counselor Assistant specializing in job matching and CV analysis.

Your expertise:
- Matching candidate CVs to suitable job positions
- Analyzing job descriptions and requirements
- Analyzing candidate profiles and skills
- Identifying skill gaps and providing learning roadmaps
- Suggesting career development paths

Response guidelines:
- Professional, encouraging, and actionable
- Data-driven based ONLY on provided context
- Specific recommendations with examples
- Honest about gaps but focus on solutions
- Use structured format for clarity

CRITICAL RULES:
1. ONLY use information from provided CONTEXT - never hallucinate
2. If information is unavailable, state "Not mentioned in CV/JD" explicitly
3. NEVER invent skills, projects, or experience not in the context
4. Always cite which CV section or JD you reference
5. For skill gaps, provide concrete, actionable learning suggestions
6. Follow the exact output format specified in the prompt

When context is missing or unclear:
- State clearly what information is missing
- Explain what additional information would be helpful
- Provide conditional recommendations ("If you have X, then Y...")
"""


# ============================================================
# INTENT-SPECIFIC PROMPTS (Structured Format)
# ============================================================

CV_ANALYSIS_PROMPT = """You are analyzing a candidate's profile and skills.

CANDIDATE PROFILE (CV):
{cv_context}

USER QUESTION:
{query}

YOU MUST respond using this EXACT structure:

## PROFILE SUMMARY
[2-3 sentence overview of the candidate's background based on CV]

## RELEVANT SKILLS & EXPERIENCE

### Technical Skills:
- [Skill 1]: [Proficiency level if mentioned] - [Evidence from CV]
- [Skill 2]: ...
[If not mentioned in CV: "Not mentioned in provided CV sections"]

### Professional Experience:
- [Position/Company]: [Duration] - [Key achievements/responsibilities relevant to query]
- ...
[If not mentioned: "Experience details not found in provided CV sections"]

### Projects (if relevant to query):
- [Project name]: [Description and technologies]
- ...
[If not mentioned: "No project information in provided CV sections"]

## ASSESSMENT

**Strengths:**
- [Strength 1 with specific evidence from CV]
- [Strength 2 with specific evidence]
- ...

**Areas for Development:**
- [Area 1 with specific gap identified]
- [Area 2 with specific gap]
- ...

## RECOMMENDATIONS
[3-5 specific, actionable next steps based on the analysis]

---

CRITICAL FORMATTING RULES:
- Use bullet points (•) for lists
- Cite specific CV sections: "From EXPERIENCE section: ..."
- If info is missing, state explicitly: "Not mentioned in CV"
- Do NOT invent or assume information not in context
- Keep technical terms accurate (e.g., "Python 3.x", "React 18")

Your analysis:"""


JD_ANALYSIS_PROMPT = """You are analyzing a job position and assessing candidate fit.

JOB DESCRIPTION:
{jd_context}

CANDIDATE PROFILE (if provided):
{cv_context}

USER QUESTION:
{query}

YOU MUST respond using this EXACT structure:

## POSITION OVERVIEW

**Role:** [Position title]
**Level:** [Intern/Junior/Middle/Senior - from JD]
**Department/Team:** [If mentioned]

## KEY REQUIREMENTS

### Must-Have Skills:
1. [Skill 1] - [Requirement details from JD]
2. [Skill 2] - ...
[If not specified in JD: "Not explicitly stated in job description"]

### Nice-to-Have Skills:
1. [Skill 1] - [Why it's a plus]
2. ...
[If not mentioned: "Not specified in JD"]

### Experience Required:
- Years: [X years in Y domain]
- Level of expertise: [Junior/Mid/Senior expectations]
- Specific experience: [e.g., "Led team of 5+", "Built scalable APIs"]

## MAIN RESPONSIBILITIES
1. [Responsibility 1 from JD]
2. [Responsibility 2 from JD]
3. ...
[If not detailed in JD: "Responsibilities not fully detailed in provided JD"]

## CANDIDATE MATCH ANALYSIS (if CV provided)

**Match Score:** [X/10] - [Brief justification]

**Matching Strengths:**
[Strength 1]: [Specific evidence from CV that matches JD requirement]
[Strength 2]: ...

**Skill Gaps:**
[Gap 1]: [Specific requirement from JD that CV doesn't show]
[Gap 2]: ...

**Recommendation:** 
[Choose one and justify]:
- **Apply Now** - Strong match, ready to apply
- **Prepare First** - Good potential but needs 1-2 skills
- **Not Suitable** - Significant gaps, consider other positions

**Reasoning:** [2-3 sentences explaining the recommendation]

## COMPENSATION & BENEFITS (if mentioned in JD)
- Salary Range: [If specified]
- Benefits: [List from JD]
- Perks: [If mentioned]
[If not mentioned: "Compensation details not provided in JD"]

## WORK ENVIRONMENT (if mentioned)
- Work Mode: [Remote/Hybrid/Onsite]
- Team Size: [If mentioned]
- Tech Stack: [Technologies used]
- Culture: [If described]
[If not mentioned: "Work environment details not provided"]

## NEXT STEPS
[3-5 specific action items for the candidate]

---

CRITICAL FORMATTING RULES:
- Use emojis for section headers (etc.)
- Match scores MUST be justified with evidence
- Gaps MUST cite specific JD requirements
- Never invent salary/benefit information
- If comparing CV to JD, cite specific sections

Your analysis:"""


JD_SEARCH_PROMPT = """You are helping match a candidate to suitable positions.

CANDIDATE PROFILE (CV):
{cv_context}

AVAILABLE JOB POSITIONS:
{jd_context}

USER QUESTION:
{query}

Analyze the question type and respond with the appropriate format:

---

## IF ASKING FOR JOB RECOMMENDATIONS:

## TOP MATCHING POSITIONS

### 1. [Position Name] - Match Score: [X/10]

**Why This Fits:**
- Matching Skill 1: [CV evidence] <--> [JD requirement]
- Matching Skill 2: ...
- Matching Skill 3: ...

**Potential Gaps:**
- Gap 1: [Missing skill from JD]
- Gap 2: ...

**Recommendation:** [Apply Now / Prepare First / Skip]
**Reasoning:** [1-2 sentences]

---

### 2. [Next Position] - Match Score: [X/10]
[Same structure]

---

## POSITION COMPARISON TABLE

| Aspect | Position 1 | Position 2 | Position 3 |
|--------|-----------|-----------|-----------|
| Match Score | X/10 | Y/10 | Z/10 |
| Your Strengths | [2-3 skills] | ... | ... |
| Gaps | [1-2 skills] | ... | ... |
| Priority | High/Medium/Low | ... | ... |

## RECOMMENDATIONS
[Which position(s) to focus on and why]

---

## IF ASKING ABOUT SKILL GAPS / LEARNING NEEDS:

## SKILL GAP ANALYSIS

### Critical Skills to Learn (Must-Have):
1. **[Skill 1]** - Required by [X positions]
   - Current Status: [From CV: "Not mentioned" or "Basic level"]
   - Target Level: [Junior/Mid/Senior level]
   - Why Important: [Explain relevance to target roles]
   - Learning Resources:
     * Course 1: [Specific course name on Udemy/Coursera]
     * Course 2: ...
     * Practice: [Specific project ideas]
   - Timeline: [X weeks to basic proficiency]

2. **[Skill 2]** - ...
   [Same structure]

### Nice-to-Have Skills (Competitive Advantage):
1. **[Skill 3]** - Found in [Y positions]
   - Why Helpful: [Explain advantage]
   - Resources: [Specific courses/certifications]
   - Timeline: [X weeks]

## LEARNING ROADMAP

**Phase 1 (Weeks 1-4):** [Focus areas]
- [Specific goals]
- [Resources to use]

**Phase 2 (Weeks 5-8):** [Next focus]
- [Build projects]
- [Get certifications]

**Phase 3 (Weeks 9-12):** [Polish]
- [Portfolio building]
- [Interview prep]

**Estimated Time to Job-Ready:** [X weeks/months]

---

## IF ASKING ABOUT LEVEL / READINESS:

## CURRENT LEVEL ASSESSMENT

**Assessed Level:** [Intern/Fresher/Junior/Middle/Senior]

**Assessment Basis:**
- Years of Experience: [X years from CV]
- Skill Depth: [Analysis of technical skills]
- Project Complexity: [From CV projects]
- Leadership/Autonomy: [Evidence from CV]

**Suitable Positions for Your Level:**
1. [Position 1] - [Match score] - [Why suitable]
2. [Position 2] - ...

**To Advance to Next Level ([Target Level]):**
- Need to develop: [Skills/experience]
- Estimated timeline: [X months/years]
- Suggested path: [Specific steps]

---

CRITICAL RULES:
- Match scores (X/10) MUST be justified with specific evidence
- All skill gaps MUST reference specific JD requirements
- Learning resources MUST be concrete (course names, platforms)
- Timelines MUST be realistic (not "1 week to learn React")
- Use CV and JD context ONLY - never hallucinate
- If information is missing, state: "Not mentioned in CV/JD"

Your response:"""


GENERAL_PROMPT = """You are a helpful career counseling assistant.

AVAILABLE CONTEXT:
{context}

USER QUESTION:
{query}

Provide helpful, professional guidance based on available context.

If you need more information (specific CV or JD), politely ask:
"To provide a detailed analysis, I would need:
- Your CV (if analyzing your profile)
- Specific job description (if analyzing a position)
- Both CV and JD (if assessing match)

Could you please provide these details or rephrase your question?"

If context is available, use it to provide a structured response.

Your response:"""


# ============================================================
# CONTEXT BUILDERS (better formatting)
# ============================================================

def build_cv_context(cv_chunks: list) -> str:
    """Build formatted CV context from retrieved chunks"""
    if not cv_chunks:
        return "No CV information available."
    
    context_parts = []
    
    for i, chunk in enumerate(cv_chunks, 1):
        payload = chunk.get("payload", {})
        section = payload.get("section", "Unknown")
        text = payload.get("chunkText", "")
        score = chunk.get("score", 0)
        
        text = text.strip()
        if not text:
            continue
        
        context_parts.append(
            f"CV Section {i}: {section} (Relevance: {score:.2f})\n{text}\n"
        )
    
    if not context_parts:
        return "No relevant CV sections found."
    
    return "\n".join(context_parts)


def build_jd_context(jd_docs: list) -> str:
    """Build formatted JD context from retrieved documents"""
    if not jd_docs:
        return "No job descriptions available."
    
    context_parts = []
    
    for i, doc in enumerate(jd_docs, 1):
        payload = doc.get("payload", {})
        position = payload.get("position", "Unknown Position")
        jd_text = payload.get("jdText", "")
        score = doc.get("score", 0)
        
        # Truncate long JDs but keep key sections
        if len(jd_text) > 1500:
            jd_text = jd_text[:1500] + "...\n[Content truncated - showing most relevant parts]"
        
        context_parts.append(
            f"Position {i}: {position} (Match Score: {score:.2f})\n{jd_text}\n"
        )
    
    if not context_parts:
        return "No relevant job positions found."
    
    return "\n".join(context_parts)


def build_combined_context(cv_chunks: list, jd_docs: list) -> str:
    """Build context with both CV and JD"""
    cv_ctx = build_cv_context(cv_chunks)
    jd_ctx = build_jd_context(jd_docs)
    
    return f"""{'='*60}
CANDIDATE PROFILE
{'='*60}
{cv_ctx}

{'='*60}
JOB POSITIONS
{'='*60}
{jd_ctx}
"""


# ============================================================
# PROMPT SELECTOR (unchanged)
# ============================================================

def get_prompt_for_intent(
    intent: str,
    query: str,
    cv_context: list = None,
    jd_context: list = None,
    conversation_history: list = None
) -> tuple[str, str]:
    """Get appropriate prompt based on intent"""
    cv_context = cv_context or []
    jd_context = jd_context or []
    
    cv_ctx = build_cv_context(cv_context)
    jd_ctx = build_jd_context(jd_context)
    
    # Build conversation history
    history_text = ""
    if conversation_history:
        history_text = "\n\n PREVIOUS CONVERSATION:\n" + "\n".join([
            f"User: {turn['query']}\nAssistant: {turn['answer'][:200]}..."
            for turn in conversation_history[-3:]
        ]) + "\n"
    
    # Select prompt based on intent
    if intent == "cv_analysis":
        user_prompt = CV_ANALYSIS_PROMPT.format(
            cv_context=cv_ctx,
            query=query
        ) + history_text
    
    elif intent == "jd_search":
        user_prompt = JD_SEARCH_PROMPT.format(
            cv_context=cv_ctx,
            jd_context=jd_ctx,
            query=query
        ) + history_text
    
    elif intent == "jd_analysis":
        user_prompt = JD_ANALYSIS_PROMPT.format(
            jd_context=jd_ctx,
            cv_context=cv_ctx,
            query=query
        ) + history_text
    
    else:  # general
        combined = build_combined_context(cv_context, jd_context)
        user_prompt = GENERAL_PROMPT.format(
            context=combined if (cv_context or jd_context) else "No CV or JD context available",
            query=query
        )
    
    return SYSTEM_PROMPT, user_prompt