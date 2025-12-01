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
User:
| email           | name   | phone       | role |
|-----------------|--------|-------------|----------|
| can1@gmail.com  | CAN1   | 0000000001  | CANDIDATE |
| can2@gmail.com  | CAN2   | 0000000002  | CANDIDATE |
| can3@gmail.com  | CAN3   | 0000000003  | CANDIDATE |
| hr1@gmail.com   | HR1    | 0000000004  | HR |
| hr2@gmail.com   | HR2    | 0000000005  | HR |
| hr3@gmail.com   | HR3    | 0000000006  | HR |
1. **Auth service**
- **Login For All**
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
- **Refresh Token For All**
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
- **Get User Data For All**
- **Name:** `/user-detail` 
  - Endpoint: /auth/user-detail
  - Method: GET
  - Description: Get user detail
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Response:
   - Success: 
     ```json
        {
            "statusCode": 200,
            "message": "User detail fetched successfully",
            "data": {
                "accessToken": "eyJhbGciOiJIUz..MF95Lec4YW48GlVbl4GXdq45_iY",
                "account": {
                    "id": "02e0d5f5-bf95-11f0-a427-de261a5dec2c",
                    "name": " Nguyen Van B",
                    "email": "vanb@example.com",
                    "phone": "0123456789",
                    "role": "CANDIDATE",
                    "createdAt": "2025-11-12T06:58:36"
                }
            },
            "timestamp": "2025-12-01T17:29:24.8982526"
        }
      ```
    - Fail:
     - Invalid request
    ```json
        {
            "statusCode": 1007,
            "message": "Refresh token is required",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
     - Unauthorized
    ```json
        {
            "statusCode": 1001,
            "message": "Unauthorized",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
     - User not found
    ```json
        {
            "statusCode": 1003,
            "message": "User not found",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
2. **Recruitment service**
- **Create Position For HR**
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
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
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
     - Missing name and level
    ```json
        {
            "statusCode": 3006,
            "message": "Missing name and level",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Filter Position For HR**
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
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
                "createdAt": "2025-10-02T14:07:23.751569",
                "updatedAt": "2025-10-02T14:07:23.751569"
            },
            {
                "id": 7,
                "name": "Developer",
                "language": "C#",
                "level": "Intern",
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
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
- **Search Position For All**
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
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
                "createdAt": "2025-10-02T14:07:23.751569",
                "updatedAt": "2025-10-02T14:07:23.751569"
            },
            {
                "id": 7,
                "name": "Developer",
                "language": "C#",
                "level": "Intern",
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
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
- **Update Position For HR**
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
     - File move failed
    ```json
        {
            "statusCode": 5002,
            "message": "File move failed",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Delete Position For HR**
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
     - Can not delete postion
    ```json
        {
            "statusCode": 3006,
            "message": "Can not delete position because it contains CVs",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ```
     - File delete failed
    ```json
        {
            "statusCode": 5001,
            "message": "File delete failed",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Upload CV For HR**
  - **Name:** `/upload` 
  - Endpoint: /upload/hr/cv
  - Method: POST
  - Description: Upload CV to store in database
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | files  | File | Yes      | abc.pdf, def.pdf,...     |
    | positionId  | Text | Yes      | 1     |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "CV uploaded successfully",
            "data": {
                "totalCv": 4,
                "batchId": "POS5_20251111_B0853",
                "message": "Your CV has been uploaded successfully and is being processed.",
                "status": "PROCESSING"
            },
            "timestamp": "2025-11-11T07:19:30.007447375"
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
     - Unauthorized action
    ```json
        {
            "statusCode": 1005,
            "message": "Unauthorized action",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Upload CV For Candidate**
  - **Name:** `/upload` 
  - Endpoint: /upload/candidate/cv
  - Method: POST
  - Description: Upload CV to store in database
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | file  | File | Yes      | abc.pdf     |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "CV uploaded successfully",
            "data": {
                "totalCv": 1,
                "batchId": "CAND_20251113_d87bbbbc",
                "message": "Your CV has been uploaded successfully and is being processed.",
                "status": "PROCESSING"
            },
            "timestamp": "2025-11-11T07:19:30.007447375"
        }
    ```
    - Fail:
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
     - Unauthorized action
    ```json
        {
            "statusCode": 1005,
            "message": "Unauthorized action",
            "data": null,
            "timestamp": "2025-10-02T17:15:29.8681381"  
        }
    ``` 
- **Get All Position For All**
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
                        "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
                        "createdAt": "2025-10-09T11:00:38.298548"
                    }
                    ...
                    {
                        "id": 5,
                        "name": "Developer",
                        "language": "Java",
                        "level": "Intern",
                        "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
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
- **CV Detail For HR**
  - **Name:** `/cv` 
  - Endpoint: /cv/{{cvId}}
  - Method: GET
  - Description: Get CV detail.
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
            "message": "CV detail retrieved successfully",
            "data": {
                "cvId": 94,
                "positionId": 15,
                "positionName": "BackEnd Java Intern",
                "email": "chi12345pham@gmail.com",
                "name": "Pham Minh Chi",
                "score": 96,
                "feedback": "Exceptional fit...testing and CI/CD.",
                "skillMatch": "Java, OOP, Spring Boot, RESTful APIs, Microservices, PostgreSQL, MySQL, Git, API Gateway, Service Discovery (Eureka), Redis, Docker, NATS",
                "skillMiss": "Unit testing (JUnit, Mockito), CI/CD",
                "status": "SCORED",
                "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
                "canRetry": false,
                "updatedAt": "2025-10-27T13:52:23.79455",
                "parsedAt": "2025-10-21T11:27:10.278149",
                "scoredAt": "2025-10-27T13:53:57.362199"
            },
            "timestamp": "2025-10-27T14:22:08.4658925"
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
- **Get CVs for specific position For HR**
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
                        "positionName": "Developer Java Intern",
                        "email": "chi12345pham@gmail.com",
                        "name": "Pham Minh Chi",
                        "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
                        "status": "PARSED",
                        "updatedAt": "2025-10-09T15:06:15.270665"
                    }
                    ...
                    {
                        "cvId": 8,
                        "positionId": 9,
                        "positionName": "Developer Java Intern",
                        "name": "",
                        "driveFileUrl": "https://drive.google.com/file/d/1-KllrO-_VQ757MV84seCYfHxbx0ycRJu/view?usp=drivesdk",
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
- **Update Candidate CV For All**
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
- **Update Candidate CV Status For HR**
  - **Name:** `/cv` 
  - Endpoint: /cv/{cvId}/{status}
  - Method: POST
  - Description: Update candidate CV status (REJECTED or APPROVED).
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
            "message": "Updated Candidate CV status successfully",
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
- **Delete Candidate CV For All**
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
- **Analysis CV For HR**
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
- **Manual Score For HR**
  - **Name:** `/analysis` 
  - Endpoint: /analysis/manual
  - Method: POST
  - Description: Manual Score for HR.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Request Body:
    ```
    {
        "cvId": 25,
        "score": 66,
        "feedback": "Good",
        "skillMatch": "Microservice",
        "skillMiss": "NATS"
    }
    ```
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "CV scored manually successfully",
            "data": {
                "cvId": 95,
                "positionId": 15,
                "email": "tranlaluot@gmail.com",
                "name": "Tran La Luot",
                "status": "SCORED",
                "scoredAt": "2025-10-27T15:51:43.6248981"
            },
            "timestamp": "2025-10-27T15:51:44.1706388"
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
     - CV already processing
    ```json
        {
            "statusCode": 2004,
            "message": "CV already processing",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
- **Retry Scoring by BatchId For HR**
  - **Name:** `/analysis` 
  - Endpoint: /analysis/retry
  - Method: POST
  - Description: Retry Score for HR.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | batchId  | Text | Yes      | POS15_20251027_161615    |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "The retry request was sent successfully. Please wait a moment.",
            "data": {
                "batchId": "POS15_20251027_161615",
                "totalRetried": 8,
                "failedToRetry": 0,
                "retriedCvIds": [76, 77, 78, 79, 88, 89, 90, 91],
                "message": "Queued 16 CVs for retry"
            },
            "timestamp": "2025-10-27T16:23:13.6859334"
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
     - No fail CVs in batch
    ```json
        {
            "statusCode": 2006,
            "message": "No failed CVs in batch",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
- **Retry Scoring by CvIds For HR**
  - **Name:** `/analysis` 
  - Endpoint: /analysis/retryCvs
  - Method: POST
  - Description: Retry Score for HR.
  - Content-Type:  `multipart/form-data`
  - Header:
    | Key            | Value                     | Required |
    |----------------|---------------------------|----------|
    | Authorization  | Bearer <accessToken> | Yes      |
  - Query Parameters
    | Key   | Type   | Required | Value |
    |-------|--------|----------|---------|
    | cvIds  | Text | Yes      | 1    |
    | cvIds  | Text | Yes      | 2    |
    ...
    | cvIds  | Text | Yes      | n    |
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "The retry request was sent successfully. Please wait a moment.",
            "data": {
                "batchId": "POS15_20251027_161615",
                "totalRetried": 2,
                "failedToRetry": 0,
                "retriedCvIds": [1, 2],
                "message": "Queued 2 CVs for retry"
            },
            "timestamp": "2025-10-27T16:23:13.6859334"
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
     - CV not failed:
    ```json
        {
            "statusCode": 2005,
            "message": "CV not failed",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
     - CVs not same position:
    ```json
        {
            "statusCode": 2007,
            "message": "CVs not same position",
            "data": null,
            "timestamp": "2025-10-13T13:59:43.5713966"
        }
    ``` 
- **Tracking CV upload/scoring For All**
  - **Name:** `/tracking` 
  - Endpoint: /tracking/{{batchId}}/status
  - Method: GET
  - Description: Track CV upload/score progress.
  - Response:
    - Success: 
    ```json
        {
            "statusCode": 200,
            "message": "Batch status retrieved successfully",
            "data": {
                "batchId": "POS15_20251027_135222",
                "processedCv": 6,
                "totalCv": 6,
                "successCv": 5,
                "failedCv": 1,
                "failedCvIds": [
                    95
                ],
                "progress": 100.0,
                "pending": 0,
                "status": "COMPLETED",
                "createdAt": "2025-10-27T13:53:36.094834",
                "completedAt": "2025-10-27T13:53:57.792611"
            },
            "timestamp": "2025-10-27T13:54:07.5327545"
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
 
   
    
    

 
   
