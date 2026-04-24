import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import Layout from '../components/Layout';
import ChatBubble from '../components/ChatBubble';
import TypingIndicator from '../components/TypingIndicator';
import { getSessionHistory, sendHrMessage, sendCandidateMessage } from '../services/chatbot';
import styles from './ChatWorkspace.module.css';


const MODE_LABELS = { HR_MODE: '👔 HR Mode', CANDIDATE_MODE: '👤 Candidate Mode' };

export default function ChatWorkspace() {
  const { sessionId } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const { user } = useAuth();

  // State passed from previous screen
  const stateData = location.state ?? {};
  const isHR = user?.role === 'HR';
  const positionId = stateData.positionId ?? null;
  const [mode, setMode] = useState(stateData.mode ?? 'HR_MODE');

  const [messages, setMessages] = useState([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const bottomRef = useRef(null);

  // Load existing history on mount
  useEffect(() => {
    getSessionHistory(sessionId)
      .then((data) => {
        const history = Array.isArray(data) ? data : (data.messages ?? []);
        setMessages(history);
      })
      .catch(() => setMessages([]))
      .finally(() => setLoading(false));
  }, [sessionId]);

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, sending]);

  const handleSend = useCallback(async () => {
    const text = input.trim();
    if (!text || sending) return;

    const userMsg = { role: 'USER', content: text, createdAt: new Date().toISOString() };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setSending(true);

    try {
      let res;
      if (isHR) {
        res = await sendHrMessage(sessionId, text, mode, Number(positionId));
      } else {
        res = await sendCandidateMessage(sessionId, text);
      }

      const reply = res.answer ?? res.reply ?? res.message ?? res.content ?? '(Không có phản hồi)';
      setMessages((prev) => [
        ...prev,
        { role: 'ASSISTANT', content: reply, createdAt: new Date().toISOString() },
      ]);
    } catch {
      setMessages((prev) => [
        ...prev,
        { role: 'ASSISTANT', content: '⚠️ Xảy ra lỗi khi kết nối chatbot. Vui lòng thử lại.', createdAt: new Date().toISOString() },
      ]);
    } finally {
      setSending(false);
    }
  }, [input, sending, isHR, sessionId, mode]);

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  }



  return (
    <Layout>
      <div className={styles.workspace}>
        {/* Top Bar */}
        <div className={styles.topBar}>
          <div className={styles.topLeft}>
            <button className={`btn btn-ghost btn-sm ${styles.backBtn}`} onClick={() => navigate(-1)}>
              ←
            </button>
            <div>
              <p className={styles.sessionTitle}>
                {isHR ? `HR Chatbot — Position #${positionId}` : 'Candidate Chatbot'}
              </p>
              <p className={styles.sessionId}>Session: {sessionId}</p>
            </div>
          </div>


        </div>

        {/* Messages */}
        <div className={styles.messages}>
          {loading && (
            <div className={styles.centerMsg}>
              <div className="spinner" style={{ width: 24, height: 24 }} />
            </div>
          )}

          {!loading && messages.length === 0 && (
            <div className={styles.welcome}>
              <div className={styles.welcomeIcon}>🤖</div>
              <p className={styles.welcomeTitle}>
                {isHR ? 'Chatbot HR sẵn sàng!' : 'Chatbot CV Review sẵn sàng!'}
              </p>
              <p className={styles.welcomeSub}>
                {isHR
                  ? 'Hãy hỏi về CV, ứng viên hoặc yêu cầu gửi email phỏng vấn.'
                  : 'Hãy hỏi về vị trí phù hợp, phân tích CV hoặc nộp đơn ứng tuyển.'}
              </p>
            </div>
          )}

          {messages.map((msg, idx) => (
            <ChatBubble key={idx} role={msg.role} content={msg.content} />
          ))}

          {sending && <TypingIndicator />}
          <div ref={bottomRef} />
        </div>

        {/* Input Area */}
        <div className={styles.inputArea}>
          <div className={styles.inputWrapper}>
            <textarea
              id="chat-input"
              className={styles.textarea}
              placeholder="Nhập tin nhắn... (Enter để gửi, Shift+Enter xuống dòng)"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={handleKeyDown}
              rows={1}
              disabled={sending}
            />
            <button
              id="btn-send"
              className={styles.sendBtn}
              onClick={handleSend}
              disabled={!input.trim() || sending}
              aria-label="Gửi tin nhắn"
            >
              <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z"/>
              </svg>
            </button>
          </div>
          <p className={styles.inputHint}>
            {isHR
              ? `Mode: ${MODE_LABELS[mode]} · Enter để gửi`
              : 'Enter để gửi · Shift+Enter xuống dòng'}
          </p>
        </div>
      </div>
    </Layout>
  );
}
