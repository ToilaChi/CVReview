# CV Review System - Technical Context Guide

**Note to AI Agent:** You are reading the system context for the CV Review project. This document serves as your permanent architectural mental model. When assisting the user, use this knowledge to accurately recommend code changes, understand module boundaries, and trace data flows without needing the user to explain the project again.

---

## 1. System Overview
The CV Review System is an automated platform designed to assist HR in screening CVs against Job Descriptions (JDs), and help Candidates with career support via AI. The system adopts an asynchronous **Microservices Architecture** to ensure high throughput, fault tolerance, and scalability.

- **Primary Languages:** Java 21 (Spring Boot utilizing Virtual Threads), Python 3 (FastAPI for AI workloads).
- **Core Features:** Intelligent Resume/JD Parsing, Vector Embeddings (RAG), Automated Resume Scoring, and Interactive AI Chatbot.

## 2. Infrastructure & Data Stores
- **RDBMS:** MySQL (accessed via Hibernate/JPA/Spring Data).
- **Vector Database:** Qdrant (Maintains collections for CV embeddings and JD embeddings to facilitate Rapid similarity search).
- **Message Broker:** RabbitMQ (`rabbitmq:3-management` on 5672/15672). Serves as the nervous system for asynchronous task processing (Parsing -> Embedding -> AI Scoring). Implements Retry and Dead Letter Queues (DLQ).
- **File Storage:** Google Drive (Primary storage for PDFs), Cloudflare R2, and Local Storage.
- **Gateway:** Spring Cloud Gateway (`api-gateway`) handling central routing, rate limiting, and JWT Auth Checks.
- **Containerization:** Handled via Docker & `docker-compose.yml` mapping environment arrays via `./env/*.env`.

## 3. Microservices Architecture
| Service | Port | Description / Responsibilities |
| :--- | :--- | :--- |
| **api-gateway** | 8080 | Single entry point. Cross-Origin Resource Sharing (CORS), JWT Authorization header extraction & validation. |
| **auth-service** | 8081 | Manages User Identities (HR / Candidate roles). Login, Registration, JWT issuing/refreshing (`accessToken`, `refreshToken`). |
| **recruitment-service** | 8082 | **Core domain.** Manages Profiles (CVs) and Positions (JDs). Integrates with **LlamaParse API** to parse files into Markdown. Generates Batch IDs (e.g. `POS{id}_{date}_B{uuid}`). Uses **Java 21 Virtual Threads** to optimize long-polling (LlamaParse takes ~60s). |
| **ai-service** | 8083 | Consumes Parsed CVs via RabbitMQ. Wraps external LLMs (**Gemini-1.5-Flash** / **Llama-3.3-70b via Groq**) to grade the candidate against the JD. Evaluates constraints like Match Score (pass >= 75), Core Fit (60%), Experience (30%), Qualification (10%). |
| **embedding-api** | 8084 | Python FastAPI app + RabbitMQ Worker instances (`worker_cv.py`, `worker_jd.py`). Processes Markdown text into Vector Embeddings using the `BAAI/bge-small-en-v1.5` model, then upserts values into Qdrant. |
| **chatbot-service** | 8085 | Experimental/WIP. Employs **LangGraph & RAG**. Analyzes user intent (`jd_search`, `cv_analysis`). Helps Candidates find matching jobs and advises skill improvement. |
| **common-library** | - | Shared module acting as an internal dependency. Contains common DTOs, global Exception Handlers, Security Configs, and Constants to reduce boilerplate code across Java services. |

## 4. Key Workflows

### **HR Flow: CV Evaluation**
1. **Upload:** HR uploads JD/CV via `/positions` or `/upload/hr/cv`.
2. **Metadata & Storage:** `recruitment-service` saves barebone records in MySQL and uploads the PDF/Doc to Google Drive/R2.
3. **Extraction:** It polls LlamaParse to convert the files to Markdown.
4. **Data Fan-Out (RabbitMQ):** Once Markdown is available, an event is emitted.
5. **Parallel Processing:**
   - `ai-service` consumes the message, calls Gemini/Groq to calculate the Match Score and produce Feedback (Missing/Matching Skills).
   - `embedding-worker-cv/jd` converts text chunks into Vectors and pushes them to Qdrant.
6. **Result:** Dashboard reads processed state from DB and shows scoring.

### **Candidate Flow: Skill Matching**
1. **Upload & Extract:** Candidate uploads CV. Parsed into Markdown.
2. **Embedding Matching:** Embedded and searched across Qdrant against existing JD vectors.
3. **Chatbot Support:** Chatbot loads context and offers direct Q&A about fit and skill gap analysis.

## 5. Important Project Mapping Rules for Agents
When changing code, respect the directory context:
- **Configs:** Found in `/BackEnd/{service-name}/src/main/resources/` (Check `application-local.yml` for local dev or `.env` vars syntax). Docker references them from `./BackEnd/env/`.
- **Database Schema Updates:** Add JPA Entity changes in `{service}/entities`. Remember the `defer-datasource-initialization: true` and `hibernate.ddl-auto: update` defaults.
- **REST APIs:** Expose API in `controllers` folders. Ensure DTO structures match the specifications outlined in the root `README.md`.
- **Library Updates:** Any shared code (e.g. standardizing Loggers, specific DTOs for RabbitMQ payload) MUST go into `/BackEnd/common-library` and other services must pull it.
- **RabbitMQ Comms:** Always verify event models are in `common-library` so both publishing service and receiving service can safely serialize/deserialize JSON.

## 6. AI & Technology Specifications
- **Models Used:** Gemini-1.5-Flash (General AI tasks), Llama-3.3-70b via Groq (Fast reasoning, scoring).
- **Embedding Model:** `bge-small-en-v1.5` (~384 dims).
- **Parser Model Setup:** LlamaParse API polling configuration is typically set to 2-second intervals, timing out gracefully at 60 seconds.