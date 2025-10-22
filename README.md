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
  - Method: POST
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
  - Method: POST
  - Description: Refresh access token when it expires using refresh token.
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
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
  - Method: POST
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
  - Method: GET
  - Description: Get position follow name, language, level.
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key      | Value   |   
    |----------|--------|
    | name     | Dev |    
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
  - Endpoint: /positions/search?keyword={position}
  - Method: GET
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
- **Update Position**
  - **Name:** `/positions` 
  - Endpoint: /positions/{positionId}
  - Method: PATCH
  - Description: Update position.
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | name  | Text | No      | Dev     |
    | language  | Text | No      | Java     |
    | level  | Text | No      | Intern     |
    | file  | File | No      | abc.pdf     |
  - Response:
    - Success:
    ```json
    {
        "statusCode": 200,
        "message": "Updated successfully",
        "data": null,
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
     - Fail save file
    ```json
        {
            "statusCode": 3005,
            "message": "Failed to save file",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Delete Position**
  - **Name:** `/positions` 
  - Endpoint: /positions/{positionId}
  - Method: DELETE
  - Description: Delete position.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Deleted successfully",
            "data": null,
            "timestamp": "2025-10-06T15:03:04.0501693"
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
     - Fail save file
    ```json
        {
            "statusCode": 3005,
            "message": "Failed to save file",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Upload CV**
  - **Name:** `/upload` 
  - Endpoint: /upload/cv
  - Method: POST
  - Description: Upload CV to store in database
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Uploaded 9/9 CVs successfully",
            "data": [
            {
                "totalCv": 3,
                "batchId": "POS8_20251020_B0ffa",
                "message": "Please wait a moment. Your CVs are being processed.",
                "status": "PROCESSING"
            },
            .....
            {
                "cvId": 31,
                "positionId": 11,
                "email": null,
                "name": null,
                "fileName": "ca96d33c-54f1-4105-9d4c-a0a5a4324e2c-ReviewCV.drawio.pdf",
                "filePath": "BackEnd/Java/Senior/CV/ca96d33c-54f1-4105-9d4c-a0a5a4324e2c-ReviewCV.drawio.pdf",
                "status": "UPLOADED",
                "updatedAt": "2025-10-08T14:30:12.7436079",
                "parsedAt": null
            }
        ],
            "timestamp": "2025-10-08T14:30:12.8960694"
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
     - Fail save file
    ```json
        {
            "statusCode": 3005,
            "message": "Failed to save file",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Get All Position**
  - **Name:** `/positions` 
  - Endpoint: /positions/all
  - Method: GET
  - Description: Get all position.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Fetched all positions successfully",
            "data": {
                "content": [
                    {
                        "id": 14,
                        "name": "FrontEnd",
                        "language": "JavaScript",
                        "level": "Senior",
                        "jdPath": "FrontEnd/JavaScript/Senior/d9ee3f55-632d-4423-ada1-a2b4c0aa461b-CV_Pham_Intern_Developer.pdf",
                        "createdAt": "2025-10-09T11:00:38.298548"
                    }
                    ...
                    {
                        "id": 5,
                        "name": "Developer",
                        "language": "Java",
                        "level": "Intern",
                        "jdPath": "Developer/Java/Intern/c568cce1-aa51-4a10-a0b2-066240c417f8-CV_Pham_Intern_Developer.pdf",
                        "createdAt": "2025-10-02T14:07:23.751569"
                    }
                ],
                "pageNumber": 0,
                "pageSize": 10,
                "totalElements": 8,
                "totalPages": 1,
                "last": true
        },
            "timestamp": 2025-10-02T17:15:29.8681381
        }
    ```
- **Get CVs for specific position**
  - **Name:** `/cv` 
  - Endpoint: /cv/position/{positionId}
  - Method: GET
  - Description: Get all position.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Fetched all CVs for position: Tester Python Fresher",
            "data": {
                "content": [
                    {
                        "cvId": 32,
                        "positionId": 9,
                        "email": "chi12345pham@gmail.com",
                        "name": "Pham Minh Chi",
                        "filePath": "Tester/Python/Fresher/CV/40b1c39b-3975-40d2-bb30-b048c43a1e16-CV_Pham_Intern_Developer.pdf",
                        "status": "PARSED",
                        "updatedAt": "2025-10-09T15:06:15.270665"
                    }
                    ...
                    {
                        "cvId": 8,
                        "positionId": 9,
                        "name": "",
                        "filePath": "Tester/Python/Fresher/CV/6252061e-9969-40f6-b8e2-a8042a7e3286-Candidate_CV.pdf",
                        "status": "PARSED",
                        "updatedAt": "2025-10-08T14:08:11.816049"
                    }
                ],
                "pageNumber": 0,
                "pageSize": 10,
                "totalElements": 7,
                "totalPages": 1,
                "last": true
        },
            "timestamp": 2025-10-02T17:15:29.8681381
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
- **Update Candidate CV**
  - **Name:** `/cv` 
  - Endpoint: /cv/{cvId}
  - Method: POST
  - Description: Update candidate CV (name, email or CV file).
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | name  | Text | No      | Pham Minh Chi     |
    | email  | Text | No      | phamminhchi@gmail.com     |
    | file  | File | No      | abc.pdf     |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Updated Candidate CV successfully",
            "data": null,
            "timestamp": "2025-10-13T13:52:06.2765845"
        }
    ```
    - Fail:
     - CV not found:
    ```json
        {
            "statusCode": 2001,
            "message": "CV not found",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
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
- **Delete Candidate CV**
  - **Name:** `/cv` 
  - Endpoint: /cv/{cvId}
  - Method: DELETE
  - Description: Delete candidate CV.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Deleted Candidate CV successfully",
            "data": null,
            "timestamp": "2025-10-13T13:53:53.9001019"
        }
    ```
    - Fail:
     - Position not found:
    ```json
        {
            "statusCode": 2001,
            "message": "CV not found",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
     - File delete fail
    ```json
        {
            "statusCode": 5001,
            "message": "File can not delete",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
- **Analysis CV**
  - **Name:** `/analysis` 
  - Endpoint: /analysis
  - Method: POST
  - Description: Analysis CV.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Required | Value |
    |-------|----------|---------|
    | positionId  | Yes      | Pham Minh Chi     |
    | cvIds  | No      | 1     |
    ...
    | cvIds  | No      | n    |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Batch created successfully",
            "data": {
                "totalCv": 4,
                "batchId": "POS15_20251020_133529",
                "message": "Please wait a moment. Your CVs are being processed.",
                "status": "PROCESSING"
            },
            "timestamp": "2025-10-20T13:35:30.3444644"
        }
    ```
    - Fail:
     - Position not found:
    ```json
        {
            "statusCode": 2001,
            "message": "CV not found",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ```
     - CV not found:
    ```json
        {
            "statusCode": 2001,
            "message": "CV not found",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
- **Tracking CV upload/scoring**
  - **Name:** `/tracking` 
  - Endpoint: /tracking/{{batchId}}/status
  - Method: GET
  - Description: Track CV upload/score progress.
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Batch status fetched successfully",
            "data": {
                "createdAt": "2025-10-20T13:35:29.187656",
                "completedAt": "2025-10-20T13:37:11.606602",
                "processedCv": 4,
                "totalCv": 4,
                "batchId": "POS15_20251020_133529",
                "type": "SCORING/UPLOAD",
                "status": "COMPLETED"
            },
            "timestamp": "2025-10-13T13:53:53.9001019"
        }
    ```
    - Fail:
     - Batch not found:
    ```json
        {
            "statusCode": 6001,
            "message": "Batch not found",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
 
   
    
    

 
   
