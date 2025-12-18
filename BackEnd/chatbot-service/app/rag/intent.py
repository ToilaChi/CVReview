from typing import Literal, Dict, List, Set
import re

IntentType = Literal["jd_search", "jd_analysis", "cv_analysis", "general"]


class IntentClassifier:
    """
    Robust intent classifier with two-stage approach:
    1. Domain detection (career-related vs general)
    2. Career intent classification (search/analysis/cv)
    
    Philosophy: Opt-out instead of opt-in
    - Assume career-related unless clearly general
    - Wide net for career keywords
    """
    
    # ============================================================
    # STAGE 1: DOMAIN DETECTION
    # ============================================================
    
    # Keywords that DEFINITELY indicate general chitchat
    DEFINITELY_GENERAL = {
        # Greetings
        "hello", "hi", "hey", "greetings", "good morning", "good afternoon",
        # Thanks
        "thank", "thanks", "thx", "appreciate",
        # Non-career topics
        "weather", "news", "joke", "story", "game", "music", "movie",
        "food", "restaurant", "travel", "sport",
        # Meta questions about the bot itself (not career)
        "what can you do", "how do you work", "who are you", "who made you",
    }
    
    # Keywords that indicate CAREER DOMAIN
    CAREER_KEYWORDS = {
        # Core career terms
        "job", "jobs", "position", "positions", "role", "roles", "career",
        "work", "employment", "opportunity", "opportunities", "opening", "openings",
        
        # CV/Resume
        "cv", "resume", "profile", "background", "qualification", "qualifications",
        "experience", "experiences", "skill", "skills", "competency", "competencies",
        "education", "degree", "certification", "certifications",
        
        # Job descriptions
        "jd", "job description", "requirement", "requirements", "responsibility",
        "responsibilities", "duty", "duties", "task", "tasks",
        
        # Application process
        "apply", "applying", "application", "interview", "candidate",
        "hire", "hiring", "recruit", "recruiting", "recruitment",
        
        # Company/Team
        "company", "companies", "employer", "team", "department", "organization",
        "startup", "corporation", "firm",
        
        # Compensation
        "salary", "salaries", "wage", "compensation", "benefit", "benefits",
        "bonus", "package", "pay",
        
        # Job levels
        "junior", "senior", "mid-level", "entry-level", "lead", "principal",
        "manager", "director", "executive", "intern", "internship",
        
        # Tech roles (common in your domain)
        "developer", "engineer", "programmer", "architect", "designer",
        "analyst", "consultant", "specialist", "technician",
        "frontend", "backend", "fullstack", "devops", "data scientist",
        
        # Skills/Tech (common mentions)
        "python", "java", "javascript", "react", "node", "sql", "api",
        "cloud", "aws", "azure", "docker", "kubernetes",
        
        # Career development
        "learn", "learning", "improve", "improve", "develop", "growth",
        "training", "course", "courses", "certification",
        "gap", "gaps", "missing", "lacking", "need", "needs",
    }
    
    # ============================================================
    # STAGE 2: CAREER INTENT PATTERNS
    # ============================================================
    
    INTENT_PATTERNS = {
        "jd_search": [
            # Explicit job search
            r"\b(find|search|show|list|recommend|suggest|give me|want).*(job|position|role|opening)",
            r"\b(job|position|role|opening).*(for me|suit|fit|match|right|good)",
            r"\b(suitable|matching|relevant|appropriate).*(job|position|role)",
            r"\bwhich (job|position|role).*(should|can|could) i (apply|try)",
            
            # Vietnamese style
            r"\b(có|tìm|xem|cho tôi).*(job|position|vị trí|công việc)",
            r"\b(vị trí|công việc).*(nào|gì|phù hợp)",
            
            # Implicit search
            r"\bwhere (should|can|could) i apply",
            r"\bwhat.*(can i|should i|could i) apply (for|to)",
            r"\b(any|got) (job|position|role|opening).*(available|open)",
            
            # Skills → Jobs mapping
            r"\bmy (skill|experience|background).*(job|position|role)",
            r"\bwhat.*(job|position|role).*(with my|given my|based on my)",
            
            # Gap analysis (học thêm gì để apply)
            r"\bwhat.*(skill|knowledge|area).*(need|should|must).*(learn|improve|develop|acquire|get)",
            r"\b(skill|knowledge|experience) gap",
            r"\bwhat.*(missing|lacking|weak).*(in my|from my).*(cv|resume|profile|skill)",
            r"\bhow.*(improve|enhance|upgrade|strengthen).*(my )?(cv|resume|profile|skill|qualification)",
            r"\bwhat.*(should i|do i need to|must i).*(learn|study|practice|get)",
            r"\bready (for|to apply)",
        ],
        
        "jd_analysis": [
            # Analyze specific JD
            r"\b(summarize|summary|describe|explain|tell me about|what is).*(jd|job description|position|role|this job)",
            r"\b(jd|job description|position|role).*(detail|requirement|responsibility|info|information)",
            
            # Requirements questions
            r"\bwhat.*(require|requirement|need|needed|qualification|skill|experience)",
            r"\b(requirement|qualification|skill|responsibility|duty).*(for|of|in) (this|the)",
            r"\bkey (requirement|skill|qualification|responsibility)",
            
            # Comparison with specific JD
            r"\b(compare|match|fit|suitable).*(me|my cv|my profile).*(with|to|against).*(jd|job|position|role|this)",
            r"\bam i (qualified|suitable|fit|ready|good enough) (for|to apply)",
            r"\b(do i|can i|should i).*(match|fit|qualify)",
            
            # Follow-up about specific JD
            r"\b(what about|how about|tell me about).*(salary|benefit|culture|team|company|location)",
            r"\bmore (detail|info|information) (about|on|regarding) (this|the|that)",
            
            # Vietnamese
            r"\b(phân tích|mô tả|yêu cầu).*(vị trí|job|jd)",
            r"\btôi có (phù hợp|đủ|qualified)",
        ],
        
        "cv_analysis": [
            # Direct CV questions
            r"\bmy (cv|resume|profile|background|experience|skill|qualification)",
            r"\b(what|how).*(my|i).*(skill|experience|strength|weakness|qualification|background)",
            r"\b(analyze|review|evaluate|assess).*(my )?(cv|resume|profile)",
            
            # Self-assessment
            r"\bwhat (am i|do i).*(good at|know|have|expert)",
            r"\bwhat.*(i have|i know|i can do|my strength)",
            r"\bhow (good|strong|qualified) (am i|is my)",
            r"\bdo i (have|know|possess)",
            
            # Skills inventory
            r"\b(show|list|tell me).*(my|i have).*(skill|experience|project|education)",
            r"\bwhat (skill|experience|project|education).*(do i have|i got|i possess)",
            
            # Vietnamese
            r"\b(kỹ năng|kinh nghiệm|trình độ|học vấn).*(của tôi|tôi có)",
            r"\btôi (giỏi|biết|có) gì",
        ],
    }
    
    # ============================================================
    # INITIALIZATION
    # ============================================================
    
    def __init__(self):
        # Compile patterns for performance
        self.compiled_patterns = {
            intent: [re.compile(pattern, re.IGNORECASE) for pattern in patterns]
            for intent, patterns in self.INTENT_PATTERNS.items()
        }
        
        # Compile domain keywords for fast lookup
        self.career_keywords_lower = {kw.lower() for kw in self.CAREER_KEYWORDS}
        self.general_keywords_lower = {kw.lower() for kw in self.DEFINITELY_GENERAL}
    
    # ============================================================
    # STAGE 1: DOMAIN DETECTION
    # ============================================================
    
    def _detect_domain(self, query: str) -> tuple[Literal["career", "general"], float]:
        """
        Detect if query is career-related or general chitchat
        
        Returns:
            (domain, confidence)
        """
        query_lower = query.lower().strip()
        words = set(re.findall(r'\b\w+\b', query_lower))
        
        # Check for definitely general keywords
        general_matches = words & self.general_keywords_lower
        if general_matches and len(query.split()) <= 10:
            # Short query with general keywords = likely general
            return ("general", 0.9)
        
        # Check for career keywords
        career_matches = words & self.career_keywords_lower
        
        if career_matches:
            # Strong career signal
            confidence = min(len(career_matches) / 3, 1.0)  # Max at 3 matches
            return ("career", max(0.7, confidence))
        
        # No clear signal → Default to career (opt-out approach)
        # Better to retrieve context unnecessarily than miss career queries
        if len(query.split()) > 5:
            # Longer query without career keywords → assume career-related
            return ("career", 0.5)
        else:
            # Very short query → likely general
            return ("general", 0.6)
    
    # ============================================================
    # STAGE 2: CAREER INTENT CLASSIFICATION
    # ============================================================
    
    def _classify_career_intent(self, query: str) -> tuple[IntentType, float, Dict[str, int]]:
        """
        Classify career-related query into specific intent
        
        Returns:
            (intent, confidence, scores)
        """
        query_lower = query.lower().strip()
        
        # Calculate match scores for each career intent
        scores = {}
        for intent in ["jd_search", "jd_analysis", "cv_analysis"]:
            patterns = self.compiled_patterns[intent]
            matches = sum(1 for pattern in patterns if pattern.search(query_lower))
            scores[intent] = matches
        
        # Get best intent
        best_intent = max(scores, key=scores.get)
        best_score = scores[best_intent]
        
        # If no pattern matches → Use keyword-based heuristics
        if best_score == 0:
            return self._fallback_classification(query_lower), 0.5, scores
        
        # Calculate confidence
        total_patterns = len(self.compiled_patterns[best_intent])
        confidence = min(best_score / max(total_patterns * 0.3, 1), 1.0)
        
        return best_intent, max(confidence, 0.6), scores
    
    def _fallback_classification(self, query_lower: str) -> IntentType:
        """
        Keyword-based heuristics when no pattern matches
        """
        # Check for JD-specific keywords
        jd_keywords = ["jd", "job description", "requirement", "responsibility", "position detail"]
        if any(kw in query_lower for kw in jd_keywords):
            return "jd_analysis"
        
        # Check for CV-specific keywords
        cv_keywords = ["my cv", "my resume", "my profile", "my skill", "my experience", "i have", "i know"]
        if any(kw in query_lower for kw in cv_keywords):
            return "cv_analysis"
        
        # Check for search keywords
        search_keywords = ["find", "search", "show", "list", "recommend", "suggest", "suitable", "match"]
        if any(kw in query_lower for kw in search_keywords):
            return "jd_search"
        
        # Default to jd_search (safest for career queries)
        return "jd_search"
    
    # ============================================================
    # MAIN CLASSIFICATION
    # ============================================================
    
    def classify(self, query: str) -> Dict[str, any]:
        """
        Two-stage classification:
        1. Detect domain (career vs general)
        2. If career → classify specific intent
        
        Args:
            query: User query string
            
        Returns:
            Dict with intent, confidence, and debug info
        """
        query = query.strip()
        
        # Stage 1: Domain detection
        domain, domain_confidence = self._detect_domain(query)
        
        if domain == "general":
            return {
                "intent": "general",
                "confidence": domain_confidence,
                "domain": "general",
                "scores": {"jd_search": 0, "jd_analysis": 0, "cv_analysis": 0},
                "query": query
            }
        
        # Stage 2: Career intent classification
        intent, intent_confidence, scores = self._classify_career_intent(query)
        
        # Combine confidences
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
        """
        Simple classification returning just the intent
        """
        result = self.classify(query)
        return result["intent"]


# Global instance
intent_classifier = IntentClassifier()


# ============================================================
# TESTING
# ============================================================

if __name__ == "__main__":
    test_queries = [
        # JD Search
        "Find suitable jobs for me",
        "What positions match my skills?",
        "Có vị trí nào phù hợp không?",
        "Where should I apply?",
        "What skills do I need to learn to be ready?",
        "Show me available jobs",
        
        # JD Analysis
        "Tell me about this position",
        "What are the requirements for this role?",
        "Am I qualified for this job?",
        "Summarize this JD",
        "Tôi có phù hợp với vị trí này không?",
        "What does this position require?",
        
        # CV Analysis
        "What Python skills do I have?",
        "How is my CV?",
        "What am I good at?",
        "Analyze my experience",
        "Do I have React skills?",
        "Kỹ năng của tôi thế nào?",
        
        # General
        "Hello, how can you help?",
        "Thanks for your help!",
        "What's the weather today?",
        "Tell me a joke",
        
        # Edge cases (should be career)
        "Backend positions?",
        "Tôi có đủ không?",
        "Python developer",
        "Senior engineer requirements",
    ]
    
    classifier = IntentClassifier()
    
    print("=" * 100)
    print("INTENT CLASSIFICATION TEST RESULTS")
    print("=" * 100)
    
    for query in test_queries:
        result = classifier.classify(query)
        
        print(f"\nQuery: {query}")
        print(f"├─ Intent: {result['intent']} (confidence: {result['confidence']:.2f})")
        print(f"├─ Domain: {result['domain']} (confidence: {result.get('domain_confidence', 0):.2f})")
        print(f"└─ Scores: {result['scores']}")
        print("-" * 100)