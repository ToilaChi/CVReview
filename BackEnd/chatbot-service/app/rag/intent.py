from typing import Literal, Dict
import re

IntentType = Literal["jd_search", "jd_analysis", "cv_analysis", "general"]


class IntentClassifier:
    """
    Simplified intent classifier using two-stage routing:
    1. Domain detection — career vs purely social/off-topic
    2. Career intent classification — which retrieval path to take

    Phase 2 simplification: ~80 overlapping regex patterns → ~15 high-precision
    signals per intent. Precision trumps recall here because the adaptive system
    prompt (Tầng 3) handles sub-intent disambiguation once the right retrieval
    path is chosen.
    """

    # ----------------------------------------------------------------
    # Stage 1 — Domain detection
    # ----------------------------------------------------------------

    _GENERAL_PHRASES = {
        "hello", "hi", "hey", "good morning", "good afternoon",
        "thank", "thanks", "appreciate",
        "weather", "news", "joke", "story", "game", "music",
        "food", "restaurant", "travel",
        "what can you do", "who are you", "who made you",
    }

    _CAREER_KEYWORDS = {
        # Job / position vocabulary
        "job", "jobs", "position", "positions", "role", "roles",
        "career", "opening", "openings", "opportunity", "opportunities",
        "vacancy", "vacancies", "recruitment", "hiring",
        # CV / profile vocabulary
        "cv", "resume", "profile", "background",
        "skill", "skills", "experience", "experiences",
        "education", "degree", "certification",
        # JD vocabulary
        "jd", "job description", "requirement", "requirements",
        "responsibility", "responsibilities",
        # Action vocabulary
        "apply", "application", "interview", "candidate",
        # Compensation
        "salary", "compensation", "benefit", "benefits", "bonus",
        # Level keywords
        "junior", "senior", "intern", "internship", "fresher", "mid-level",
        # Tech role keywords
        "developer", "engineer", "programmer", "analyst",
        "frontend", "backend", "fullstack", "devops",
    }

    # ----------------------------------------------------------------
    # Stage 2 — Career intent patterns (15 per intent, high precision)
    # ----------------------------------------------------------------

    _INTENT_PATTERNS: Dict[str, list] = {
        #
        # jd_search — "which job fits me, compare jobs, apply, find positions"
        # Retrieval: CV + JD (scoring path or chunk path)
        #
        "jd_search": [
            r"\b(find|search|recommend|suggest).{0,20}(job|position|role)",
            r"\b(job|position|role).{0,20}(suit|fit|match|right for me)",
            r"\b(which|what).{0,20}(job|position|role).{0,20}(should|can|could) i",
            r"\b(i want to|looking for|interested in).{0,20}(job|position|role|apply)",
            r"\b(suitable|matching|compatible).{0,20}(job|position|role)",
            r"\bwhere.{0,20}(should|can|could) i apply",
            r"\b(compare|choose|decide).{0,20}(position|role|job)",
            r"\b(am i|can i).{0,20}(intern|junior|senior|fresher)",
            r"\b(skill|experience).{0,20}(gap|missing|need to learn|what to learn)",
            r"\b(how to|what to).{0,20}(improve|learn|prepare).{0,20}(career|job|apply)",
            r"\b(compatible|suitability|match score|fit score)",
            r"\b(ready to apply|ready for|good enough for)\b",
        ],

        #
        # jd_analysis — "details of THIS job: salary, benefits, process, stack"
        # Retrieval: JD section chunks only
        #
        "jd_analysis": [
            r"\b(salary|compensation|wage|pay|stipend)\b",
            r"\b(benefit|insurance|parking|lunch|bonus)\b",
            r"\b(interview|recruitment|hiring).{0,20}(process|round|stage|step)",
            r"\b(how many|how long).{0,20}(round|interview|year|month|experience)",
            r"\b(work|working).{0,20}(environment|culture|team|atmosphere)",
            r"\b(remote|onsite|hybrid|work from home)\b",
            r"\b(tech stack|technology|framework|tool|stack)\b",
            r"\b(career path|promotion|advancement|grow|mentorship|training)\b",
            r"\b(requirement|qualification|must.have|mandatory).{0,20}(skill|experience|degree)",
            r"\bthis (position|job|role)\b",
            r"\b(tell me about|describe|explain|summarize).{0,20}(position|job|role|jd)",
            r"\b(online test|coding challenge|technical test|live coding)\b",
        ],

        #
        # cv_analysis — "my CV strengths, gaps, what I have"
        # Retrieval: CV chunks only
        #
        "cv_analysis": [
            r"\bmy (cv|resume|profile)\b",
            r"\bmy (skill|skills|experience|background|qualification)\b",
            r"\b(analyze|review|evaluate|assess).{0,20}my (cv|resume|profile)",
            r"\bwhat (do i have|skill|experience).{0,20}(i have|i got|i possess)",
            r"\b(my|i have|i know).{0,20}(strength|weakness|gap|advantage)\b",
            r"\b(do i have|do i know|can i).{0,20}(python|java|react|node|sql|docker|spring)",
            r"\b(how good|how strong|how qualified|how experienced).{0,20}(am i|is my)",
            r"\b(what|which).{0,20}project.{0,20}(i did|have i|did i)\b",
            r"\bshould i.{0,20}(get|obtain|add|include).{0,20}(certification|course|degree)\b",
            r"\b(improve|strengthen|enhance).{0,20}my (cv|resume|profile|skill)\b",
            r"\b(list|show|tell).{0,20}my.{0,20}(project|experience|skill|education)\b",
            r"\bhow.{0,20}(would you|do you).{0,20}(rate|assess|describe).{0,20}(me|my)\b",
        ],
    }

    def __init__(self):
        self._compiled: Dict[str, list] = {
            intent: [re.compile(p, re.IGNORECASE | re.DOTALL) for p in patterns]
            for intent, patterns in self._INTENT_PATTERNS.items()
        }
        self._career_kw_lower = {kw.lower() for kw in self._CAREER_KEYWORDS}
        self._general_phrases_lower = {p.lower() for p in self._GENERAL_PHRASES}

    # ----------------------------------------------------------------
    # Stage 1 helpers
    # ----------------------------------------------------------------

    def _detect_domain(self, query: str) -> tuple[Literal["career", "general"], float]:
        q = query.lower().strip()
        words = set(re.findall(r'\b\w+\b', q))

        # Short queries with pure social phrases are general
        if words & self._general_phrases_lower and len(query.split()) <= 8:
            return "general", 0.9

        career_hits = words & self._career_kw_lower
        if career_hits:
            confidence = min(len(career_hits) / 3, 1.0)
            return "career", max(0.7, confidence)

        # Longer queries without explicit career keywords default to career
        if len(query.split()) > 5:
            return "career", 0.5
        return "general", 0.6

    # ----------------------------------------------------------------
    # Stage 2 — career intent scoring
    # ----------------------------------------------------------------

    def _classify_career_intent(
        self, query: str
    ) -> tuple[IntentType, float, Dict[str, int]]:
        q = query.lower().strip()
        scores: Dict[str, int] = {}

        for intent, patterns in self._compiled.items():
            scores[intent] = sum(1 for p in patterns if p.search(q))

        best_intent: str = max(scores, key=scores.get)  # type: ignore[arg-type]
        best_score: int = scores[best_intent]

        if best_score == 0:
            # Semantic fallback based on lightweight keyword counting
            return self._semantic_fallback(q), 0.5, scores

        total_patterns = len(self._compiled[best_intent])
        confidence = min(best_score / max(total_patterns * 0.25, 1), 1.0)
        if best_score >= 2:
            confidence = min(confidence + 0.1, 1.0)

        return best_intent, max(confidence, 0.6), scores  # type: ignore[return-value]

    def _semantic_fallback(self, q_lower: str) -> IntentType:
        """
        Lightweight fallback when no pattern fires.
        Counts presence of semantic indicator phrases.
        """
        jd_search_score = sum(1 for w in [
            "find", "search", "recommend", "suitable", "match",
            "apply", "option", "which", "level", "compare",
        ] if w in q_lower)

        jd_analysis_score = sum(1 for w in [
            "salary", "benefit", "process", "interview", "round",
            "environment", "culture", "remote", "hybrid", "stack",
            "tech", "requirement", "career path", "this position",
        ] if w in q_lower)

        cv_analysis_score = sum(1 for w in [
            "my cv", "my resume", "my skill", "my experience",
            "i have", "i know", "do i have", "improve my",
            "my gap", "my weakness", "my strength",
        ] if w in q_lower)

        # Context boosters
        if any(ph in q_lower for ph in ["this position", "this job", "this role"]):
            jd_analysis_score += 2
        if re.search(r'\bmy \w+', q_lower):
            cv_analysis_score += 1
        if re.search(r'\b(which|what).{0,15}(position|job|role)', q_lower):
            jd_search_score += 1

        best = max(
            [("jd_search", jd_search_score), ("jd_analysis", jd_analysis_score), ("cv_analysis", cv_analysis_score)],
            key=lambda t: t[1],
        )
        return best[0] if best[1] > 0 else "jd_search"  # type: ignore[return-value]

    # ----------------------------------------------------------------
    # Public interface
    # ----------------------------------------------------------------

    def classify(self, query: str) -> Dict[str, object]:
        query = query.strip()
        domain, domain_conf = self._detect_domain(query)

        if domain == "general":
            return {
                "intent": "general",
                "confidence": domain_conf,
                "domain": "general",
                "scores": {"jd_search": 0, "jd_analysis": 0, "cv_analysis": 0},
                "query": query,
            }

        intent, intent_conf, scores = self._classify_career_intent(query)
        return {
            "intent": intent,
            "confidence": domain_conf * intent_conf,
            "domain": "career",
            "domain_confidence": domain_conf,
            "intent_confidence": intent_conf,
            "scores": scores,
            "query": query,
        }

    def classify_simple(self, query: str) -> IntentType:
        return self.classify(query)["intent"]  # type: ignore[return-value]


# Global singleton
intent_classifier = IntentClassifier()