from typing import Literal, Dict, List, Set
import re

IntentType = Literal["jd_search", "jd_analysis", "cv_analysis", "general"]


class IntentClassifier:
    """
    Robust intent classifier with two-stage approach:
    1. Domain detection (career-related vs general)
    2. Career intent classification (search/analysis/cv)
    """
    
    # ============================================================
    # STAGE 1: DOMAIN DETECTION 
    # ============================================================
    
    DEFINITELY_GENERAL = {
        "hello", "hi", "hey", "greetings", "good morning", "good afternoon",
        "thank", "thanks", "thx", "appreciate",
        "weather", "news", "joke", "story", "game", "music", "movie",
        "food", "restaurant", "travel", "sport",
        "what can you do", "how do you work", "who are you", "who made you",
    }
    
    CAREER_KEYWORDS = {
        "job", "jobs", "position", "positions", "role", "roles", "career",
        "work", "employment", "opportunity", "opportunities", "opening", "openings",
        "cv", "resume", "profile", "background", "qualification", "qualifications",
        "experience", "experiences", "skill", "skills", "competency", "competencies",
        "education", "degree", "certification", "certifications",
        "jd", "job description", "requirement", "requirements", "responsibility",
        "responsibilities", "duty", "duties", "task", "tasks",
        "apply", "applying", "application", "interview", "candidate",
        "hire", "hiring", "recruit", "recruiting", "recruitment",
        "company", "companies", "employer", "team", "department", "organization",
        "startup", "corporation", "firm",
        "salary", "salaries", "wage", "compensation", "benefit", "benefits",
        "bonus", "package", "pay",
        "junior", "senior", "mid-level", "entry-level", "lead", "principal",
        "manager", "director", "executive", "intern", "internship",
        "developer", "engineer", "programmer", "architect", "designer",
        "analyst", "consultant", "specialist", "technician",
        "frontend", "backend", "fullstack", "devops", "data scientist",
        "python", "java", "javascript", "react", "node", "sql", "api",
        "cloud", "aws", "azure", "docker", "kubernetes",
        "learn", "learning", "improve", "develop", "growth",
        "training", "course", "courses", "certification",
        "gap", "gaps", "missing", "lacking", "need", "needs",
    }
    
    # ============================================================
    # STAGE 2: EXPANDED CAREER INTENT PATTERNS
    # ============================================================
    
    INTENT_PATTERNS = {
        "jd_search": [
            # Original patterns (kept)
            r"\b(find|search|show|list|recommend|suggest|give me|want).*(job|position|role|opening)",
            r"\b(job|position|role|opening).*(for me|suit|fit|match|right|good)",
            r"\b(suitable|matching|relevant|appropriate).*(job|position|role)",
            r"\bwhich (job|position|role).*(should|can|could) i (apply|try)",
            r"\b(cÃ³|tÃ¬m|xem|cho tÃ´i).*(job|position|vá»‹ trÃ­|cÃ´ng viá»‡c)",
            r"\b(vá»‹ trÃ­|cÃ´ng viá»‡c).*(nÃ o|gÃ¬|phÃ¹ há»£p)",
            r"\bwhere (should|can|could) i apply",
            r"\bwhat.*(can i|should i|could i) apply (for|to)",
            r"\b(any|got) (job|position|role|opening).*(available|open)",
            r"\bmy (skill|experience|background).*(job|position|role)",
            r"\bwhat.*(job|position|role).*(with my|given my|based on my)",
            r"\bwhat.*(skill|knowledge|area).*(need|should|must).*(learn|improve|develop|acquire|get)",
            r"\b(skill|knowledge|experience) gap",
            r"\bwhat.*(missing|lacking|weak).*(in my|from my).*(cv|resume|profile|skill)",
            r"\bhow.*(improve|enhance|upgrade|strengthen).*(my )?(cv|resume|profile|skill|qualification)",
            r"\bwhat.*(should i|do i need to|must i).*(learn|study|practice|get)",
            r"\bready (for|to apply)",
            
            # Natural question patterns
            r"^(what|which).*(job|position|role).*(suitable|suit|fit|match|right|good|appropriate)",
            r"^(what|which).*(job|position|role).*(i can|can i|should i)",
            r"\b(job|position|role).*(for me|for my|match my|suit my|fit my)",
            
            # Apply-related
            r"\bapply.*(where|which|what|for)",
            r"\bi (want|need|looking for|interested in).*(job|position|role)",
            r"\b(career|job).*(option|choice|opportunity|opportunities)",
            
            # Question words at start
            r"^where (can|should|could|do) i (apply|work|go|find)",
            r"^what.*(available|open|vacancy|vacancies)",
            
            # Skill → Job mapping (CRITICAL!)
            r"\b(with|given|based on|considering).*(my|this|the).*(cv|resume|profile|skill|experience|background)",
            r"\bmy (skill|experience|background).*(lead to|qualify for|suitable for|good for)",
            r"\b(what|which).*(can i do|am i qualified for).*(with|given)",
            
            # Level questions (use case 1 - IMPORTANT!)
            r"\b(which|what) (level|seniority).*(should|can|could|am i)",
            r"\b(intern|junior|middle|mid|senior|fresher).*(position|role|level|job)",
            r"\bshould i apply.*(intern|junior|middle|senior)",
            r"\b(am i|can i).*(intern|junior|middle|senior)",
            
            # Comparison questions (use case 7)
            r"\bdifference.*(between|position|role|job)",
            r"\b(choose|decide|pick|select).*(between|backend|frontend|fullstack|position|role)",
            r"\bshould i (apply|choose|go).*(or|for|to)",
            r"\b(compare|comparison).*(position|role|job)",
            
            # Percentage/compatibility (use case 1)
            r"\b(compatibility|match|matching).*(percentage|percent|score)",
            r"\bhow (compatible|suitable|fit)",
            
            # Casual phrasings
            r"\b(show|tell) me.*(job|position|role)",
            r"\b(got|have|any).*(opening|vacancy|job available)",
        ],
        
        "jd_analysis": [
            # Original patterns (kept)
            r"\b(summarize|summary|describe|explain|tell me about|what is).*(jd|job description|position|role|this job)",
            r"\b(jd|job description|position|role).*(detail|requirement|responsibility|info|information)",
            r"\b(requirement|qualification|skill|responsibility|duty).*(for|of|in) (this|the)",
            r"\bkey (requirement|skill|qualification|responsibility)",
            r"\b(compare|match|fit|suitable).*(me|my cv|my profile).*(with|to|against).*(jd|job|position|role|this)",
            r"\bam i (qualified|suitable|fit|ready|good enough) (for|to apply)",
            r"\b(do i|can i|should i).*(match|fit|qualify)",
            r"\b(what about|how about|tell me about).*(salary|benefit|culture|team|company|location)",
            r"\bmore (detail|info|information) (about|on|regarding) (this|the|that)",
            r"\b(phÃ¢n tÃ­ch|mÃ´ táº£|yÃªu cáº§u).*(vá»‹ trÃ­|job|jd)",
            r"\btÃ´i cÃ³ (phÃ¹ há»£p|Ä'á»§|qualified)",
            
            # Requirements questions (use case 2 - CRITICAL!)
            r"\b(what|which).*(skill|technology|tech|tool|requirement|qualification|experience).*(need|require|must|mandatory|necessary)",
            r"\b(minimum|mandatory|required|necessary|must-have|essential).*(skill|experience|qualification|requirement)",
            r"\b(how many|how much).*(year|years|experience|month)",
            r"\b(junior|middle|mid|senior|intern|fresher).*(require|requirement|need|qualification)",
            
            # Specific requirement details
            r"\b(what|which).*(language|framework|tool|technology|database)",
            r"\b(do|does).*(require|need).*(degree|certification|experience)",
            r"\bis.*(required|needed|mandatory|necessary)",
            
            # Salary & Benefits (use case 4)
            r"\b(salary|compensation|pay|wage|benefit|bonus|stipend|package)",
            r"\bhow much.*(earn|pay|salary|make|get paid)",
            r"\b(salary range|pay range|compensation range)",
            r"\b(bonus|benefit|insurance|parking|lunch)",
            
            # Work environment (use case 6)
            r"\b(work|working|team|culture|environment|atmosphere)",
            r"\b(remote|onsite|hybrid|wfh|work from home)",
            r"\b(tech stack|technology|tool|stack|framework)",
            r"\b(how many).*(people|member|developer|engineer).*(team)",
            r"\b(code review|coding standard|best practice)",
            
            # Recruitment process (use case 5)
            r"\b(recruitment|interview|hiring).*(process|round|step|stage)",
            r"\bhow many.*(round|interview|step|stage|test)",
            r"\b(online test|live coding|coding challenge|technical test)",
            r"\b(response time|how long|wait|waiting)",
            r"\b(interview).*(english|vietnamese|language)",
            
            # Career path (use case 3)
            r"\b(career|advance|advancement|promotion|grow|growth|path|progression)",
            r"\bhow long.*(take|need|require).*(become|promote|advance|move up)",
            r"\b(tech lead|technical|management|manager).*(direction|path|track)",
            r"\b(training|mentorship|mentor|learning|development)",
            
            # Match/fit questions (use case 1)
            r"\b(am i|can i be).*(good fit|suitable|qualified|ready)",
            r"\b(strength|weakness).*(compare|comparison|vs|versus).*(position|jd)",
            r"\bif i apply.*(position|role|job)",
            r"\b(compatibility|match score|matching).*(with|for|this)",
        ],
        
        "cv_analysis": [
            # Original patterns (kept)
            r"\bmy (cv|resume|profile|background|experience|skill|qualification)",
            r"\b(what|how).*(my|i).*(skill|experience|strength|weakness|qualification|background)",
            r"\b(analyze|review|evaluate|assess).*(my )?(cv|resume|profile)",
            r"\bwhat (am i|do i).*(good at|know|have|expert)",
            r"\bwhat.*(i have|i know|i can do|my strength)",
            r"\bhow (good|strong|qualified) (am i|is my)",
            r"\bdo i (have|know|possess)",
            r"\b(show|list|tell me).*(my|i have).*(skill|experience|project|education)",
            r"\bwhat (skill|experience|project|education).*(do i have|i got|i possess)",
            r"\b(ká»¹ nÄƒng|kinh nghiá»‡m|trÃ¬nh Ä'á»™|há»c váº¥n).*(cá»§a tÃ´i|tÃ´i cÃ³)",
            r"\btÃ´i (giá»i|biáº¿t|cÃ³) gÃ¬",
            
            # CV improvement questions (use case 8 - CRITICAL!)
            r"\b(what|which).*(skill|project|experience|certification|course).*(missing|lack|need|should add|must add)",
            r"\b(how to|how can|how should).*(improve|enhance|strengthen|upgrade|boost).*(my )?(cv|resume|profile)",
            r"\b(gap|weakness|weak point|missing|lacking).*(my|in my|from my).*(cv|resume|profile|skill)",
            r"\bshould i (add|include|learn|get|obtain|acquire)",
            r"\bwhat.*(to add|to improve|to learn|to include)",
            
            # Comparison with JD
            r"\b(compare|comparison|match|compatibility).*(my|cv|resume|profile).*(with|to|against|vs)",
            r"\b(strength|advantage|weakness|disadvantage).*(compare|comparison|vs|versus)",
            r"\b(where|what).*(strong|weak|good|bad).*(compare)",
            r"\b(better|worse|stronger|weaker).*(than|compare)",
            
            # Readiness assessment
            r"\b(am i|can i be).*(ready|qualified|suitable|good enough|prepared)",
            r"\b(do i have|do i meet|do i satisfy).*(requirement|qualification|skill|criteria)",
            r"\b(can i|could i|should i).*(apply|try|go for)",
            r"\b(qualify|qualified|qualification).*(for|to apply)",
            
            # Specific skill queries
            r"\b(do i have|do i know|can i).*(python|java|react|node|sql|docker)",
            r"\b(my|i have|i know).*(python|java|react|node|sql|docker|programming)",
            r"\bhow (good|strong|proficient|experienced).*(am i|my).*(in|at|with)",
            
            # Project/experience queries
            r"\b(what|which).*(project|work|experience|job).*(have i|did i|i did)",
            r"\b(list|show|tell).*(my )?(project|experience|work history)",
            r"\bhow many.*(year|years|month).*(experience|i have)",
            
            # Education/certification
            r"\b(education|degree|university|college|certification|certificate)",
            r"\bshould i.*(get|obtain|take|pursue).*(certification|certificate|degree|course)",
            
            # General self-assessment
            r"\b(what|where).*(am i|my).*(strength|strong|good|advantage)",
            r"\b(what|where).*(am i|my).*(weakness|weak|bad|lacking)",
            r"\b(how).*(would you|do you).*(rate|assess|evaluate|describe).*(me|my)",
        ],
    }
    
    # ============================================================
    # INITIALIZATION
    # ============================================================
    
    def __init__(self):
        self.compiled_patterns = {
            intent: [re.compile(pattern, re.IGNORECASE) for pattern in patterns]
            for intent, patterns in self.INTENT_PATTERNS.items()
        }
        
        self.career_keywords_lower = {kw.lower() for kw in self.CAREER_KEYWORDS}
        self.general_keywords_lower = {kw.lower() for kw in self.DEFINITELY_GENERAL}
    
    # ============================================================
    # STAGE 1: DOMAIN DETECTION 
    # ============================================================
    
    def _detect_domain(self, query: str) -> tuple[Literal["career", "general"], float]:
        query_lower = query.lower().strip()
        words = set(re.findall(r'\b\w+\b', query_lower))
        
        general_matches = words & self.general_keywords_lower
        if general_matches and len(query.split()) <= 10:
            return ("general", 0.9)
        
        career_matches = words & self.career_keywords_lower
        
        if career_matches:
            confidence = min(len(career_matches) / 3, 1.0)
            return ("career", max(0.7, confidence))
        
        if len(query.split()) > 5:
            return ("career", 0.5)
        else:
            return ("general", 0.6)
    
    # ============================================================
    # STAGE 2: IMPROVED CAREER INTENT CLASSIFICATION
    # ============================================================
    
    def _classify_career_intent(self, query: str) -> tuple[IntentType, float, Dict[str, int]]:
        query_lower = query.lower().strip()
        
        scores = {}
        for intent in ["jd_search", "jd_analysis", "cv_analysis"]:
            patterns = self.compiled_patterns[intent]
            matches = sum(1 for pattern in patterns if pattern.search(query_lower))
            scores[intent] = matches
        
        best_intent = max(scores, key=scores.get)
        best_score = scores[best_intent]
        
        if best_score == 0:
            # Use semantic fallback instead of simple keyword heuristics
            return self._semantic_fallback_classification(query_lower), 0.5, scores
        
        # Improved confidence calculation
        total_patterns = len(self.compiled_patterns[best_intent])
        confidence = min(best_score / max(total_patterns * 0.2, 1), 1.0)  # Changed from 0.3 to 0.2
        
        # Bonus confidence if multiple patterns match
        if best_score >= 2:
            confidence = min(confidence + 0.1, 1.0)
        
        return best_intent, max(confidence, 0.6), scores
    
    def _semantic_fallback_classification(self, query_lower: str) -> IntentType:
        """
        IMPROVED: Semantic classification with signal counting
        """
        # Count intent signals (more comprehensive)
        jd_search_signals = 0
        jd_analysis_signals = 0
        cv_analysis_signals = 0
        
        # JD Search signals (finding jobs)
        search_indicators = [
            "find", "search", "show", "list", "recommend", "suggest",
            "suitable", "match", "fit", "right", "good for",
            "which position", "what job", "where apply", "where should",
            "level", "intern", "junior", "middle", "senior",
            "choose", "decide", "option", "opportunity",
            "compatibility", "percentage", "score",
            "available", "opening", "vacancy"
        ]
        jd_search_signals = sum(1 for w in search_indicators if w in query_lower)
        
        # JD Analysis signals (specific JD details)
        analysis_indicators = [
            "requirement", "qualification", "responsibility",
            "salary", "benefit", "compensation", "pay", "bonus",
            "process", "interview", "round", "test",
            "this position", "this job", "this role", "the position",
            "career path", "advancement", "promotion",
            "work environment", "team", "culture",
            "remote", "onsite", "hybrid",
            "tech stack", "technology", "tool",
            "how many year", "how much experience"
        ]
        jd_analysis_signals = sum(1 for w in analysis_indicators if w in query_lower)
        
        # CV Analysis signals (self-assessment)
        cv_indicators = [
            "my cv", "my resume", "my profile", "my skill", "my experience",
            "i have", "i know", "i can", "do i have", "do i know",
            "improve my", "strengthen my", "enhance my",
            "my gap", "my weakness", "my strength",
            "should i add", "what to add", "what to learn",
            "am i qualified", "am i ready", "can i apply",
            "my background", "my qualification",
            "assess me", "evaluate me", "rate me"
        ]
        cv_analysis_signals = sum(1 for w in cv_indicators if w in query_lower)
        
        # Additional context clues
        # If mentions "this/the position/job/role" → likely jd_analysis
        if any(phrase in query_lower for phrase in ["this position", "this job", "this role", "the position", "the job"]):
            jd_analysis_signals += 2
        
        # If has "my/i" pronouns → likely cv_analysis
        if any(phrase in query_lower for phrase in ["my ", "i have", "i am", "i can", "do i"]):
            cv_analysis_signals += 1
        
        # If asks "which/what" + job-related → likely jd_search
        if re.search(r'\b(which|what).*(position|job|role|level)', query_lower):
            jd_search_signals += 1
        
        signals = {
            "jd_search": jd_search_signals,
            "jd_analysis": jd_analysis_signals,
            "cv_analysis": cv_analysis_signals
        }
        
        best = max(signals, key=signals.get)
        
        # Better tie-breaking
        if signals[best] == 0:
            # No clear signal → default based on query structure
            if "?" in query_lower and len(query_lower.split()) < 10:
                # Short question → likely jd_search
                return "jd_search"
            else:
                return "jd_search"  # Safe default
        
        # If tie, use additional heuristics
        if list(signals.values()).count(signals[best]) > 1:
            # Multiple intents with same score → use length heuristic
            if len(query_lower.split()) < 8:
                return "jd_search"  # Short queries usually search
            else:
                return "jd_analysis"  # Longer queries usually analyze
        
        return best
    
    # ============================================================
    # MAIN CLASSIFICATION 
    # ============================================================
    
    def classify(self, query: str) -> Dict[str, any]:
        query = query.strip()
        
        domain, domain_confidence = self._detect_domain(query)
        
        if domain == "general":
            return {
                "intent": "general",
                "confidence": domain_confidence,
                "domain": "general",
                "scores": {"jd_search": 0, "jd_analysis": 0, "cv_analysis": 0},
                "query": query
            }
        
        intent, intent_confidence, scores = self._classify_career_intent(query)
        
        final_confidence = domain_confidence * intent_confidence
        
        return {
            "intent": intent,
            "confidence": final_confidence,
            "domain": "career",
            "domain_confidence": domain_confidence,
            "intent_confidence": intent_confidence,
            "scores": scores,
            "query": query
        }
    
    def classify_simple(self, query: str) -> IntentType:
        result = self.classify(query)
        return result["intent"]


# Global instance
intent_classifier = IntentClassifier()