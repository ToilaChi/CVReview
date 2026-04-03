run embedding
cd .\BackEnd\embedding-service\
.\venv\Scripts\activate
python -m app.main   
python -m app.worker_cv
python -m app.worker_jd

run chatbot 
.\venv\Scripts\activate
python -m app.main   
streamlit run app/streamlit_app.py  -- UI chatbot

link get refresh token GG Drive
https://developers.google.com/oauthplayground/
https://www.googleapis.com/auth/drive