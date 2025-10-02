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
2. **Recruitment service**
- **Create Position**
  - **Name:** `/positions` 
  - Endpoint: /positions
  - Method: Post
  - Description: Create position with attached JD.
  - Content-Type:  `multipart/form-data`

  - Form Data Parameters:

    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | name  | Text | Yes      | Dev     |
    | language  | Text | Yes      | Java     |
    | level  | Text | Yes      | Intern     |
    | file  | File | Yes      | abc.pdf     |

  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Success",
            "data": {
                "id": 9,
                "name": "Tester",
                "language": "",
                "level": "Fresher",
                "jdPath": "D:/CVReview/.../Developer.pdf",
                "createdAt": "2025-10-02T14:13:54.4057562",
                "updatedAt": "2025-10-02T14:13:54.4057562"
            },
            "timestamp": "2025-10-02T14:13:54.5487176"
        }
    ```
    - Fail:
     - Duplicate position:
    ```json
        {
            "statusCode": 3002,
            "message": "Position already exists",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
     - File parse fail
    ```json
        {
            "statusCode": 3004,
            "message": "Failed to parse JD",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
     - File not found
    ```json
        {
            "statusCode": 3003,
            "message": "File not found",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
     - Fail save file
    ```json
        {
            "statusCode": 3005,
            "message": "Failed to save file",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Filter Position**
  - **Name:** `/positions` 
  - Endpoint: /positions
  - Method: Get
  - Description: Get position follow name, language, level.
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key      | Value   |   
    |----------|--------|
    | name     | Dev |    
    | language | Java |      
    | level    | Intern |   
  - Response:
    - Success:
    ```json
    {
        "statusCode": 200,
        "message": "Success",
        "data": [
            {
                "id": 5,
                "name": "Developer",
                "language": "Java",
                "level": "Intern",
                "jdPath": "D:/CVReview/.../Developer.pdf",
                "createdAt": "2025-10-02T14:07:23.751569",
                "updatedAt": "2025-10-02T14:07:23.751569"
            },
            {
                "id": 7,
                "name": "Developer",
                "language": "C#",
                "level": "Intern",
                "jdPath": "D:/CVReview/BackEnd/...Developer.pdf",
                "createdAt": "2025-10-02T14:11:52.262104",
                "updatedAt": "2025-10-02T14:11:52.262104"
            }
        ],
        "timestamp": "2025-10-02T15:19:56.363315"
    }
    ```
    - Fail:
     - Position not found:
    ```json
        {
            "statusCode": 3001,
            "message": "Position not found",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Search Position**
  - **Name:** `/positions/search` 
  - Endpoint: /positions/search?keyword={{position}}
  - Method: Get
  - Description: Get position follow name, language, level.
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key      | Value   |   
    |----------|--------|
    | keyword     | Dev |  
  - Response:
    - Success:
    ```json
    {
        "statusCode": 200,
        "message": "Success",
        "data": [
            {
                "id": 5,
                "name": "Developer",
                "language": "Java",
                "level": "Intern",
                "jdPath": "D:/CVReview/.../Developer.pdf",
                "createdAt": "2025-10-02T14:07:23.751569",
                "updatedAt": "2025-10-02T14:07:23.751569"
            },
            {
                "id": 7,
                "name": "Developer",
                "language": "C#",
                "level": "Intern",
                "jdPath": "D:/CVReview/BackEnd/...Developer.pdf",
                "createdAt": "2025-10-02T14:11:52.262104",
                "updatedAt": "2025-10-02T14:11:52.262104"
            }
        ],
        "timestamp": "2025-10-02T15:19:56.363315"
    }
    ```
    - Fail:
     - Position not found:
    ```json
        {
            "statusCode": 3001,
            "message": "Position not found",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
    

 
   
