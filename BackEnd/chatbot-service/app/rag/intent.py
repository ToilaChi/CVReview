from typing import Literal, Dict
import re

IntentType = Literal["jd_search", "jd_analysis", "general"]


class IntentClassifier:
    """
    Lightweight intent classifier using keyword matching
    Fast and deterministic for demo purposes
    """
    
    # Intent patterns (keywords that indicate specific intents)
    PATTERNS = {
    "jd_search": [
        # Tìm job phù hợp
        r"\b(find|search|show|list|recommend|suggest) (jobs?|positions?|roles?|openings?)\b",
        r"\bsuitable (jobs?|positions?|roles?) (for me|for my (cv|profile|skills?))\b",
        r"\bwhat (jobs?|positions?|roles?).*\b(suitable|fit|match|right)\b",
        r"\b(which|what) (jobs?|positions?|roles?).*\b(should|can|could) i apply\b",
        r"\bjob (recommendations?|suggestions?)\b",
        r"\bpositions? (for|matching) my (cv|profile|skills?|experience)\b",
        # Câu hỏi nâng cao về gap analysis
        r"\bwhat.*(areas?|skills?|knowledge).*\b(need|should|must).*\b(learn|improve|develop|enhance)\b",
        r"\b(skill|knowledge) gaps?\b",
        r"\bwhat.*missing.*\b(in my|from my) (cv|profile|skills?)\b",
        r"\bhow.*(improve|enhance|upgrade).*\b(my )?(cv|profile|skills?|qualifications?)\b",
        r"\bwhat.*(lacking|weak|need work)\b",
        ],
        
        "jd_analysis": [
            # Phân tích JD cụ thể
            r"\bsummarize.*\b(jd|job description|position)\b",
            r"\btell me (about|more about).*\b(jd|job|position|role)\b",
            r"\bwhat (is|are).*\b(requirements?|responsibilities|qualifications?)\b",
            r"\b(describe|explain|detail).*\b(jd|job|position|role)\b",
            r"\bkey (requirements?|skills?|qualifications?)\b",
            r"\bjob details?\b",
            r"\bposition (description|requirements?|details?)\b",
            # Câu hỏi follow-up về JD cụ thể
            r"\b(what about|how about|tell me about) (salary|benefits?|culture|team)\b",
            r"\bmore (details?|info|information) (about|on|regarding)\b",
        ],
        
        "general": [
            r"\b(hello|hi|hey|greetings?)\b",
            r"\b(thank|thanks)\b",
            r"\b(help|assist|guide)\b",
            r"\bwhat (can|do) you do\b",
            r"\bhow (does|do) (this|it|you) work\b",
        ]
    }
    
    def __init__(self):
        # Compile patterns for performance
        self.compiled_patterns = {
            intent: [re.compile(pattern, re.IGNORECASE) for pattern in patterns]
            for intent, patterns in self.PATTERNS.items()
        }
    
    def classify(self, query: str) -> Dict[str, any]:
        """
        Classify user query into intent
        
        Args:
            query: User query string
            
        Returns:
            Dict with intent and confidence scores
        """
        query_lower = query.lower().strip()
        
        # Calculate match scores for each intent
        scores = {}
        for intent, patterns in self.compiled_patterns.items():
            matches = sum(1 for pattern in patterns if pattern.search(query_lower))
            scores[intent] = matches
        
        # Get best intent
        best_intent = max(scores, key=scores.get)
        best_score = scores[best_intent]
        
        # If no clear match, default to general
        if best_score == 0:
            return {
                "intent": "general",
                "confidence": 0.3,
                "scores": scores,
                "query": query
            }
        
        # Calculate confidence (normalized by number of patterns)
        total_patterns = len(self.compiled_patterns[best_intent])
        confidence = min(best_score / total_patterns, 1.0)
        
        return {
            "intent": best_intent,
            "confidence": confidence,
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
# USAGE EXAMPLES
# ============================================================

if __name__ == "__main__":
    # Test cases
    test_queries = [
        "What skills do I have in Python?",
        "Find suitable jobs for me",
        "Compare my CV with the Software Engineer position",
        "How can I improve my resume?",
        "Show me all available positions",
        "Am I qualified for this role?",
        "Hello, how can you help me?",
        "What's the weather like?",  # Edge case
    ]
    
    classifier = IntentClassifier()
    
    print("Intent Classification Test Results:\n")
    print("=" * 80)
    
    for query in test_queries:
        result = classifier.classify(query)
        print(f"\nQuery: {query}")
        print(f"Intent: {result['intent']} (confidence: {result['confidence']:.2f})")
        print(f"Scores: {result['scores']}")
        print("-" * 80)