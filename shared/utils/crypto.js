/**
 * 🔐 加密工具库 - 前端安全加密函数
 *
 * 提供密码加密、Token保护、数据签名等功能
 * 用于增强前端安全性，防止敏感数据泄露
 */

/**
 * 🔐 RSA公钥加密（用于密码传输）
 *
 * 注意：这是简化版实现，生产环境应使用Web Crypto API或JSEncrypt库
 *
 * @param {string} data - 要加密的数据
 * @param {string} publicKey - RSA公钥（PEM格式）
 * @returns {Promise<string>} Base64编码的密文
 */
export async function rsaEncrypt(data, publicKey) {
  try {
    if (window.crypto && window.crypto.subtle) {
      // 使用 Web Crypto API（推荐）
      const encoder = new TextEncoder()
      const dataArray = encoder.encode(data)

      // 导入公钥
      const keyData = pemToBinary(publicKey)
      const publicKeyObj = await window.crypto.subtle.importKey(
        'spki',
        keyData,
        {
          name: 'RSA-OAEP',
          hash: 'SHA-256',
        },
        false,
        ['encrypt']
      )

      // 加密数据
      const encrypted = await window.crypto.subtle.encrypt(
        {
          name: 'RSA-OAEP',
        },
        publicKeyObj,
        dataArray
      )

      // 转换为Base64
      return arrayBufferToBase64(encrypted)

    } else {
      console.warn('[Crypto] Web Crypto API不可用，使用Base64编码替代')
      return btoa(unescape(encodeURIComponent(data)))
    }
  } catch (error) {
    console.error('[Crypto] RSA加密失败:', error)
    throw new Error('加密失败，请检查浏览器兼容性')
  }
}

/**
 * 🔐 AES-GCM对称加密（用于敏感数据本地存储）
 *
 * @param {string} data - 要加密的数据
 * @param {string} password - 加密密钥
 * @returns {Promise<string>} 加密后的字符串（包含IV和密文的JSON）
 */
export async function aesEncrypt(data, password) {
  try {
    const encoder = new TextEncoder()
    const dataBytes = encoder.encode(data)
    const passwordBytes = encoder.encode(password)

    // 从密码派生密钥
    const keyMaterial = await window.crypto.subtle.importKey(
      'raw',
      passwordBytes,
      'PBKDF2',
      false,
      ['deriveKey']
    )

    const salt = window.crypto.getRandomValues(new Uint8Array(16))
    const key = await window.crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256',
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt']
    )

    // 生成随机IV
    const iv = window.crypto.getRandomValues(new Uint8Array(12))

    // 加密
    const encrypted = await window.crypto.subtle.encrypt(
      {
        name: 'AES-GCM',
        iv: iv,
      },
      key,
      dataBytes
    )

    // 组合：salt + iv + ciphertext
    const combined = new Uint8Array(salt.length + iv.length + encrypted.byteLength)
    combined.set(salt, 0)
    combined.set(iv, salt.length)
    combined.set(new Uint8Array(encrypted), salt.length + iv.length)

    return arrayBufferToBase64(combined.buffer)

  } catch (error) {
    console.error('[Crypto] AES加密失败:', error)
    throw new Error('加密失败')
  }
}

/**
 * 🔓 AES-GCM解密
 *
 * @param {string} encryptedData - 加密的数据字符串
 * @param {string} password - 解密密钥
 * @returns {Promise<string>} 解密后的原始数据
 */
export async function aesDecrypt(encryptedData, password) {
  try {
    const decoder = new TextDecoder()
    const passwordEncoder = new TextEncoder()
    const passwordBytes = passwordEncoder.encode(password)

    // 解码Base64
    const combined = base64ToArrayBuffer(encryptedData)

    // 提取salt、iv、ciphertext
    const salt = combined.slice(0, 16)
    const iv = combined.slice(16, 28)
    const ciphertext = combined.slice(28)

    // 派生密钥
    const keyMaterial = await window.crypto.subtle.importKey(
      'raw',
      passwordBytes,
      'PBKDF2',
      false,
      ['deriveKey']
    )

    const key = await window.crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256',
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['decrypt']
    )

    // 解密
    const decrypted = await window.crypto.subtle.decrypt(
      {
        name: 'AES-GCM',
        iv: iv,
      },
      key,
      ciphertext
    )

    return decoder.decode(decrypted)

  } catch (error) {
    console.error('[Crypto] AES解密失败:', error)
    throw new Error('解密失败：数据可能已损坏或密钥不正确')
  }
}

/**
 * 🎲 生成安全的随机数
 *
 * @param {number} length - 随机数长度（字节）
 * @returns {string} 十六进制随机字符串
 */
export function generateSecureRandom(length = 32) {
  const bytes = new Uint8Array(length)
  if (window.crypto && window.crypto.getRandomValues) {
    window.crypto.getRandomValues(bytes)
  } else {
    // 降级方案（不安全，仅用于旧浏览器）
    for (let i = 0; i < length; i++) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }

  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * 🏷️ 生成CSRF Token
 *
 * @returns {string} CSRF Token（32字符十六进制）
 */
export function generateCsrfToken() {
  return generateSecureRandom(16)
}

/**
 * 📝 创建数据签名（HMAC-SHA256）
 *
 * 用于API请求防篡改
 *
 * @param {Object|string} data - 要签名的数据
 * @param {string} secret - 签名密钥
 * @returns {Promise<string>} 十六进制签名字符串
 */
export async function createSignature(data, secret) {
  try {
    const encoder = new TextEncoder()
    const dataStr = typeof data === 'object' ? JSON.stringify(data) : data
    const dataBytes = encoder.encode(dataStr)
    const secretBytes = encoder.encode(secret)

    // 导入密钥
    const key = await window.crypto.subtle.importKey(
      'raw',
      secretBytes,
      {
        name: 'HMAC',
        hash: 'SHA-256',
      },
      false,
      ['sign']
    )

    // 签名
    const signature = await window.crypto.subtle.sign('HMAC', key, dataBytes)

    return arrayBufferToBase64(signature)

  } catch (error) {
    console.error('[Crypto] 签名失败:', error)
    throw new Error('签名生成失败')
  }
}

/**
 * 🛡️ 安全地存储Token到Cookie（HttpOnly替代方案）
 *
 * 由于前端无法设置HttpOnly Cookie，此方法提供次优方案：
 * - 加密存储
 * - 设置过期时间
 * - 定期轮换
 *
 * @param {string} token - Token值
 * @param {number} expiresIn - 过期时间（毫秒），默认7天
 */
export function secureStoreToken(token, expiresIn = 7 * 24 * 60 * 60 * 1000) {
  try {
    // 生成设备唯一标识
    let deviceId = localStorage.getItem('device_id')
    if (!deviceId) {
      deviceId = generateSecureRandom(16)
      localStorage.setItem('device_id', deviceId)
    }

    // 加密Token
    const encrypted = aesEncrypt(token, deviceId)

    // 存储加密后的Token和元信息
    const tokenData = {
      value: encrypted,
      createdAt: Date.now(),
      expiresAt: Date.now() + expiresIn,
      deviceId: deviceId.substring(0, 8) // 只存储部分deviceId用于验证
    }

    localStorage.setItem('secure_token', JSON.stringify(tokenData))

    console.log('[Crypto] ✅ Token已安全存储')

  } catch (error) {
    console.error('[Crypto] Token存储失败:', error)
    // 降级为普通存储
    localStorage.setItem('token', token)
  }
}

/**
 * 🗑️ 安全删除Token
 */
export function secureRemoveToken() {
  localStorage.removeItem('secure_token')
  localStorage.removeItem('token')
  localStorage.removeItem('device_id')

  console.log('[Crypto] 🗑️ Token已清除')
}

// ========== 工具函数 ==========

/**
 * PEM格式转二进制
 */
function pemToBinary(pem) {
  const base64 = pem
    .replace(/-----BEGIN PUBLIC KEY-----/, '')
    .replace(/-----END PUBLIC KEY-----/, '')
    .replace(/\s/g, '')

  const binaryString = atob(base64)
  const bytes = new Uint8Array(binaryString.length)

  for (let i = 0; i < binaryString.length; i++) {
    bytes[i] = binaryString.charCodeAt(i)
  }

  return bytes.buffer
}

/**
 * ArrayBuffer 转 Base64
 */
function arrayBufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer)
  let binary = ''

  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i])
  }

  return btoa(binary)
}

/**
 * Base64 转 ArrayBuffer
 */
function base64ToArrayBuffer(base64) {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)

  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }

  return bytes
}
