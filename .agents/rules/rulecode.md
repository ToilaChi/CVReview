---
trigger: always_on
---

## AI Agent Coding & Implementation Standards
1. Developer-Centric Commenting
Avoid "AI-Style" Annotations: Do not use comments like "Fixed this line for optimization" or "Updated logic for X."

Focus on the "Why," Not the "What": Write comments that explain the rationale behind complex logic or specific business rules. If the code is self-explanatory, prioritize clean naming over redundant comments.

Standard Documentation: Use industry-standard docstrings (e.g., Javadoc for Spring Boot, Google-style for Python, or Dartdoc for Flutter) that are concise and meaningful.

2. Radical Codebase Cleanup
Dead Code Elimination: Immediately after applying new logic, identify and remove any methods, variables, classes, or imports that have become obsolete or unused.

Refactor, Don't Append: When a feature is replaced, delete the old implementation entirely. Do not leave "zombie code" (old code commented out) in the file.

Redundancy Check: Ensure that new changes do not introduce duplicate logic. Consolidate functions if the new change covers existing functionality.

3. Engineering Excellence Rules
D.R.Y (Don't Repeat Yourself): If a logic pattern appears more than once, extract it into a reusable private method or a utility class.

Robust Error Handling: Always wrap critical logic in try-catch blocks using specific Custom Exceptions rather than generic error types.

Strict Typing: Maintain type safety. Define explicit data types for all parameters and return values; avoid using any or loose typing.

Architectural Consistency: Strictly follow the naming conventions of the current project (e.g., camelCase for Java/Flutter, snake_case for Python).

4. Implementation Workflow
Atomic Updates: Each code modification should focus on a single responsibility or task.

Technical Summary: After completing a task, provide a brief, technical summary of the changes made and their impact on the system architecture.