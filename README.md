# CV Review System
## 1. Objective
The system allows HR to upload CV and JD, uses LLM (OpenAI/Gemini) to analyze CV, compare with JD, return match score, feedback, and structured extraction (skills, email, name, experience).
## 2. Scope of work
The project will be composed of the following microservices:

- Api-gateway: Single entry (Spring Cloud Gateway) for routing, auth check, rate limiting.
- Auth-service: user register/login, JWT issuance, refresh tokens, role management, logout.
- Recruitment-service: Manage recruitment positions (position/JD) and candidate profiles (CV)
- AI-service: Analyze CV content with AI/LLM, save evaluation results and feedback to the system.
## 3. API endpoints
1. **Auth service**
- **Login**
  - **Name:** `/login` 
  - Endpoint: /auth/login
  - Method: Post
  - Description: Authenticates the user and returns an accessToken and refreshToken on success.
  - Request body:
    ```json
    {
	    "phone": "0987654321",
        "password": "password123"
    }
    ```
  - Response:
   - Success: 
     ```json
        {
         "data": {
            "accessToken": "eyJhbGciOiJ...",
            "refreshToken": "eyJhbGciOiJIUzI...",
            "account": {
                 "id": "b42f1fb...b763ab1ea7",
                 "name": "Nguyen Van A",
                 "role": "HR"
            }
         },
         "message": "Welcome to CV Review System"
        }
      ```
  - Fail
   - Missing phone or password:
     ```json
        {
         "data": null,
         "message": "Phone is required"
        }
     ``` 
   - Wrong phone or password:
     ```json
        {
         "data": null,
         "message": "Phone is incorrect. Please enter again"
        }
- **Refresh Token**
- **Name:** `/refresh-token` 
  - Endpoint: /auth/refresh-token
  - Method: Post
  - Description: Refresh access token when it expires using refresh token.
  - Header:
    ```json
        Content-type: application/json
        Header: Bearer {{refreshToken}}
    ```
  - Request body:
    ```json
        {
	       "refreshToken": "eyJhbGciOiJIUzI..."    
        }
    ```
  - Response:
   - Success: 
     ```json
        {
           "message": "",
           "data": {
                "refreshToken": "eyJhbGciOiJIUzI1Ni...",
                "accessToken": "eyJhbGciOiJIUzI1..."
               }
        }
      ```
 
   
