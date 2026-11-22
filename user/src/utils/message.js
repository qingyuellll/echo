/**
 * 统一消息提示工具
 *
 * 替代原生alert()，提供更好的用户体验
 *
 * 使用方式：
 *   import { showMessage } from '@/utils/message'
 *   showMessage('操作成功', 'success')
 *   showMessage('座位已锁定', 'warning')
 *   showMessage('网络异常', 'error')
 */

const MESSAGE_CONFIG = {
  duration: 3000,  // 显示时长（毫秒）
  position: 'top', // 位置: top / center / bottom
}

let messageContainer = null

function createMessageContainer() {
  if (messageContainer) return messageContainer

  messageContainer = document.createElement('div')
  messageContainer.id = 'echo-message-container'
  messageContainer.style.cssText = `
    position: fixed;
    top: 20px;
    left: 50%;
    transform: translateX(-50%);
    z-index: 99999;
    display: flex;
    flex-direction: column;
    gap: 10px;
    pointer-events: none;
  `

  document.body.appendChild(messageContainer)
  return messageContainer
}

const STYLE_MAP = {
  success: {
    background: '#f6ffed',
    border: '#b7eb8f',
    color: '#389e0d',
    icon: '✅'
  },
  error: {
    background: '#fff2f0',
    border: '#ffccc7',
    color: '#cf1322',
    icon: '❌'
  },
  warning: {
    background: '#fffbe6',
    border: '#ffe58f',
    color: '#d48806',
    icon: '⚠️'
  },
  info: {
    background: '#e6f7ff',
    border: '#91d5ff',
    color: '#1890ff',
    icon: 'ℹ️'
  }
}

export function showMessage(content, type = 'info', duration = MESSAGE_CONFIG.duration) {
  const container = createMessageContainer()
  const style = STYLE_MAP[type] || STYLE_MAP.info

  const messageEl = document.createElement('div')
  messageEl.style.cssText = `
    padding: 12px 24px;
    background: ${style.background};
    border: 1px solid ${style.border};
    border-radius: 6px;
    color: ${style.color};
    font-size: 14px;
    font-weight: 500;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    display: flex;
    align-items: center;
    gap: 8px;
    animation: slideIn 0.3s ease-out;
    pointer-events: auto;
    max-width: 400px;
    white-space: pre-wrap;
    word-break: break-word;
  `

  // 安全性：使用textContent而非innerHTML，防止XSS攻击
  const iconSpan = document.createElement('span')
  iconSpan.textContent = style.icon
  const contentSpan = document.createElement('span')
  contentSpan.textContent = content
  messageEl.appendChild(iconSpan)
  messageEl.appendChild(contentSpan)

  container.appendChild(messageEl)

  setTimeout(() => {
    messageEl.style.animation = 'slideOut 0.3s ease-in forwards'
    setTimeout(() => {
      if (messageEl.parentNode) {
        messageEl.parentNode.removeChild(messageEl)
      }
      if (container.children.length === 0) {
        document.body.removeChild(container)
        messageContainer = null
      }
    }, 300)
  }, duration)
}

if (typeof document !== 'undefined') {
  const styleSheet = document.createElement('style')
  styleSheet.textContent = `
    @keyframes slideIn {
      from { opacity: 0; transform: translateY(-20px); }
      to { opacity: 1; transform: translateY(0); }
    }
    @keyframes slideOut {
      from { opacity: 1; transform: translateY(0); }
      to { opacity: 0; transform: translateY(-20px); }
    }
  `
  document.head.appendChild(styleSheet)
}

export default {
  success: (msg) => showMessage(msg, 'success'),
  error: (msg) => showMessage(msg, 'error'),
  warning: (msg) => showMessage(msg, 'warning'),
  info: (msg) => showMessage(msg, 'info'),
  show: showMessage
}
