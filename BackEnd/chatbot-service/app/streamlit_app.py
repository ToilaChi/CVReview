import streamlit as st
import requests
import json

st.set_page_config(page_title="Career Counselor", page_icon="💼")

# API endpoint
API_URL = "http://localhost:8080/chatbot/chat"

# Session state
if "messages" not in st.session_state:
    st.session_state.messages = []
if "conversation_history" not in st.session_state:
    st.session_state.conversation_history = []
if "jwt_token" not in st.session_state:
    st.session_state.jwt_token = ""

# UI
st.title("💼 Career Counselor Chatbot")
st.caption("Ask about job matching and career guidance")

# Sidebar
with st.sidebar:
    st.header("Authentication")
    jwt_token = st.text_input("JWT Token", type="password", value=st.session_state.jwt_token)
    if jwt_token:
        st.session_state.jwt_token = jwt_token
    
    st.header("Settings")
    # Không cần candidate_id nữa - sẽ lấy từ JWT
    cv_id = st.number_input("CV ID (optional)", value=None, min_value=1)
    jd_id = st.number_input("JD ID (optional)", value=None, min_value=1)
    
    if st.button("Clear Chat"):
        st.session_state.messages = []
        st.session_state.conversation_history = []
        st.rerun()

# Display chat history
for msg in st.session_state.messages:
    with st.chat_message(msg["role"]):
        st.markdown(msg["content"])
        if msg["role"] == "assistant" and "metadata" in msg:
            with st.expander("📊 Metadata"):
                st.json(msg["metadata"])

# Chat input
if prompt := st.chat_input("Ask me about jobs..."):
    # Add user message
    st.session_state.messages.append({"role": "user", "content": prompt})
    with st.chat_message("user"):
        st.markdown(prompt)
    
    # Call API
    with st.chat_message("assistant"):
        with st.spinner("Thinking..."):
            try:
                payload = {
                    "query": prompt,
                    "cv_id": cv_id if cv_id else None,
                    "jd_id": jd_id if jd_id else None,
                    "conversation_history": st.session_state.conversation_history
                }
                
                headers = {
                    "Authorization": f"Bearer {st.session_state.jwt_token}",
                    "Content-Type": "application/json"
                }
                
                response = requests.post(API_URL, json=payload, headers=headers)
                response.raise_for_status()
                data = response.json()
                
                answer = data["answer"]
                metadata = data["metadata"]
                
                st.markdown(answer)
                with st.expander("📊 Metadata"):
                    st.json(metadata)
                
                st.session_state.messages.append({
                    "role": "assistant",
                    "content": answer,
                    "metadata": metadata
                })
                st.session_state.conversation_history.append({
                    "query": prompt,
                    "answer": answer
                })
                
            except requests.exceptions.HTTPError as e:
                if e.response.status_code == 401:
                    st.error("Authentication failed. Please check your JWT token.")
                elif e.response.status_code == 403:
                    st.error("Access denied. HR role cannot use chatbot.")
                else:
                    st.error(f"Error: {e.response.text}")
            except Exception as e:
                st.error(f"Error: {str(e)}")