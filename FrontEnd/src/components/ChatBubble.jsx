import styles from './ChatBubble.module.css';

/**
 * @param {string} role  - 'USER' | 'ASSISTANT'
 * @param {string} content - nội dung tin nhắn
 */
export default function ChatBubble({ role, content }) {
  const isUser = role === 'USER';

  return (
    <div className={`${styles.row} ${isUser ? styles.rowUser : styles.rowBot}`}>
      {!isUser && (
        <div className={styles.avatar} title="AI Assistant">
          🤖
        </div>
      )}
      <div className={`${styles.bubble} ${isUser ? styles.bubbleUser : styles.bubbleBot}`}>
        {formatContent(content)}
      </div>
    </div>
  );
}

/** Render xuống dòng và giữ các đoạn phân cách */
function formatContent(text) {
  return text.split('\n').map((line, i) => (
    <span key={i}>
      {line}
      {i < text.split('\n').length - 1 && <br />}
    </span>
  ));
}
