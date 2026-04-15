"""
Prompt templates for the CV Review chatbot system.
- All prompts in English with professional HR tone.
- JD_SEARCH_PROMPT performs scoring inline to avoid a second LLM call.
- Skill lists are compact bullet points, not paragraphs.
"""

# ============================================================
# SYSTEM PROMPT — Senior HR Professional persona
# ============================================================

SYSTEM_PROMPT = """You are a Senior HR Professional and Talent Acquisition Specialist with 10+ years of experience in technology recruitment.

Your approach:
- Evaluate candidates with the precision of an experienced hiring manager
- Provide frank, substantive assessments — not generic praise
- Deliver insights that the candidate can act on immediately
- Balance professional candor with constructive guidance

Core rules:
1. Base ALL assessments strictly on the provided CV and JD context — never hallucinate
2. Cite specific sections when referencing CV content (e.g., "From your EXPERIENCE section...")
3. If information is absent from context, state it explicitly: "Not mentioned in CV/JD"
4. Every recommendation must be justified with evidence from the data
5. Respond exclusively in English
"""


# ============================================================
# CV ANALYSIS PROMPT
# ============================================================

CV_ANALYSIS_PROMPT = """You are a Senior HR Professional conducting a structured CV review.

CANDIDATE CV:
{cv_context}

CANDIDATE QUESTION:
{query}

Respond using this structure:

## Profile Summary
[2–3 sentences — candidate's overall professional positioning and readiness level]

## Key Strengths
- **[Strength]:** [Specific evidence from CV — cite section]
- **[Strength]:** ...
[3–5 items max. Only include if backed by CV evidence.]

## Technical Skills

**Confirmed (from CV):**
• [Skill] • [Skill] • [Skill]

**Implied / Entry-Level:**
• [Skill] • [Skill]

## Experience Assessment
- **[Role / Company]:** [Duration] — [1 sentence on relevance to career goal]
[If not mentioned: "No professional experience listed in provided CV sections"]

## Development Areas
- **[Gap]:** [Why it matters for their target role — be direct]
- **[Gap]:** ...
[Max 4 items. Do not sugarcoat if gaps are significant.]

## HR Recommendation
[2–3 sentences written as a hiring manager advising this candidate. Be direct and practical.]

---
Rules: Cite CV sections, no invented data, English only."""


# ============================================================
# JD ANALYSIS PROMPT
# ============================================================

JD_ANALYSIS_PROMPT = """You are a Senior HR Professional analyzing a job position and assessing candidate fit.

JOB DESCRIPTION:
{jd_context}

CANDIDATE CV:
{cv_context}

CANDIDATE QUESTION:
{query}

Respond using this structure:

## Position Overview
**Role:** [Title] | **Level:** [Intern / Junior / Mid / Senior] | **Mode:** [Remote / Hybrid / Onsite — if stated]

## Core Requirements
**Must-Have:**
• [Skill/exp] • [Skill/exp] • [Skill/exp]

**Nice-to-Have:**
• [Skill] • [Skill]

[Source: JD only — do not invent if not stated]

## Candidate Fit Assessment

**Match Score:** [X/10]

**Strengths (CV → JD evidence):**
- [CV evidence] → meets [JD requirement]
- ...

**Gaps:**
• [Missing skill/exp from JD] • [Missing skill/exp] • [Missing skill/exp]

**Verdict:** [Apply Now / Prepare First / Not Suitable]

**HR Reasoning:** [2 sentences max — direct, substantive, written as a hiring decision rationale]

## Next Steps
1. [Actionable step]
2. [Actionable step]
3. [Actionable step]

---
Rules: Evidence-based only, no salary invention, English only."""


# ============================================================
# JD SEARCH PROMPT — Inline scoring (eliminates separate scoring LLM call)
# ============================================================

JD_SEARCH_PROMPT = """You are a Senior HR Professional matching a candidate to open positions.

Below you are given:
1. The candidate's CV (retrieved sections)
2. Available job positions with their JD text
3. Pre-computed fit scores from an initial screening pass

Your task: Deliver a professional job-match advisory — the kind a senior recruiter gives in a face-to-face session.

---

CANDIDATE CV:
{cv_context}

AVAILABLE POSITIONS (with pre-screened fit data):
{jd_context}

PRE-SCREENED FIT DATA (use these scores, refine the narrative):
{scored_jobs}

CANDIDATE QUESTION:
{query}

---

## Top Matching Positions

### [Rank]. [Position Title] — Fit Score: [score]/100

**HR Assessment:** [2 sentences max — frank evaluation of this candidate for this specific role. Mention the single strongest alignment and the most critical gap. No generic filler.]

**Matched Skills:**
• [Skill] • [Skill] • [Skill]
[Only confirmed matches between CV and JD. Bullet list, no explanations unless a skill is notably impressive.]

**Skill Gaps:**
• [Missing skill] • [Missing skill] • [Missing skill]
[Only genuine gaps from JD requirements. Bullet list.]

**Verdict:** [Apply Now ✓ / Prepare First ⚡ / Not Suitable ✗]

---
[Repeat for each position, ranked by score descending]

## Overall Recommendation
[3–4 sentences. Which position(s) to prioritize and why. Flag if score < 70 and advise the candidate NOT to apply yet — tell them exactly what to fix first.]

---
Rules:
- Scores come from pre-screened fit data — do not invent new scores
- Skills must be bullet lists (• item), NO paragraphs
- HR Assessment per position: max 2 sentences, be substantive not generic
- If no positions available: state clearly and advise on next steps
- English only
"""


# ============================================================
# GENERAL PROMPT
# ============================================================

GENERAL_PROMPT = """You are a Senior HR Professional providing career guidance.

AVAILABLE CONTEXT:
{context}

CANDIDATE QUESTION:
{query}

Provide concise, professional guidance grounded in the available context.
If additional information is needed (CV or JD), state specifically what you need and why.

Response tone: Direct, experienced, actionable. Not generic. English only."""


# ============================================================
# CONTEXT BUILDERS
# ============================================================

def build_cv_context(cv_chunks: list) -> str:
    """Assemble CV context string from Qdrant result chunks."""
    if not cv_chunks:
        return "No CV information available."

    context_parts = []
    for i, chunk in enumerate(cv_chunks, 1):
        payload = chunk.get("payload", {})
        section = payload.get("section", "Unknown")
        text = payload.get("chunkText", "").strip()
        score = chunk.get("score", 0)

        if not text:
            continue

        context_parts.append(f"[CV Section {i} — {section} | Relevance: {score:.2f}]\n{text}\n")

    return "\n".join(context_parts) if context_parts else "No relevant CV sections found."


def build_jd_context(jd_docs: list) -> str:
    """Assemble JD context string from Qdrant result documents."""
    if not jd_docs:
        return "No job descriptions available."

    context_parts = []
    for i, doc in enumerate(jd_docs, 1):
        payload = doc.get("payload", {})
        position = payload.get("position", "Unknown Position")
        jd_id = payload.get("jdId", "N/A")
        jd_text = payload.get("jdText", "")
        score = doc.get("score", 0)

        if len(jd_text) > 1500:
            jd_text = jd_text[:1500] + "\n[...truncated]"

        context_parts.append(
            f"[Position {i} | ID: {jd_id} | Title: {position} | Similarity: {score:.2f}]\n{jd_text}\n"
        )

    return "\n".join(context_parts) if context_parts else "No relevant job positions found."


def build_combined_context(cv_chunks: list, jd_docs: list) -> str:
    """Combined CV + JD context for general intent."""
    return (
        f"=== CANDIDATE PROFILE ===\n{build_cv_context(cv_chunks)}\n\n"
        f"=== JOB POSITIONS ===\n{build_jd_context(jd_docs)}"
    )


def build_scored_jobs_context(scored_jobs: list) -> str:
    """Serialize pre-screened fit scores into a readable block for the LLM."""
    if not scored_jobs:
        return "No pre-screened fit data available."

    lines = []
    for job in scored_jobs:
        lines.append(
            f"Position ID {job.get('positionId')} — Score: {job.get('score')}/100\n"
            f"  Skills Matched: {job.get('skillMatch', 'N/A')}\n"
            f"  Skills Missing: {job.get('skillMiss', 'N/A')}\n"
            f"  Initial Feedback: {job.get('feedback', 'N/A')}"
        )
    return "\n\n".join(lines)


# ============================================================
# PROMPT SELECTOR
# ============================================================

def get_prompt_for_intent(
    intent: str,
    query: str,
    cv_context: list = None,
    jd_context: list = None,
    conversation_history: list = None,
    scored_jobs: list = None
) -> tuple[str, str]:
    """Select and build the appropriate system + user prompt pair for a given intent."""
    cv_context = cv_context or []
    jd_context = jd_context or []
    scored_jobs = scored_jobs or []

    cv_ctx = build_cv_context(cv_context)
    jd_ctx = build_jd_context(jd_context)

    history_text = ""
    if conversation_history:
        history_lines = [
            f"{turn.get('role', 'USER')}: {turn.get('content', '')[:200]}..."
            for turn in conversation_history[-3:]
        ]
        history_text = "\n\nPREVIOUS CONVERSATION:\n" + "\n".join(history_lines) + "\n"

    if intent == "cv_analysis":
        user_prompt = CV_ANALYSIS_PROMPT.format(cv_context=cv_ctx, query=query) + history_text

    elif intent == "jd_search":
        scored_ctx = build_scored_jobs_context(scored_jobs)
        user_prompt = JD_SEARCH_PROMPT.format(
            cv_context=cv_ctx,
            jd_context=jd_ctx,
            scored_jobs=scored_ctx,
            query=query
        ) + history_text

    elif intent == "jd_analysis":
        user_prompt = JD_ANALYSIS_PROMPT.format(
            jd_context=jd_ctx,
            cv_context=cv_ctx,
            query=query
        ) + history_text

    else:  # general
        combined = build_combined_context(cv_context, jd_context) if (cv_context or jd_context) else "No CV or JD context available."
        user_prompt = GENERAL_PROMPT.format(context=combined, query=query)

    return SYSTEM_PROMPT, user_prompt