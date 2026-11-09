import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 1_800_000 // 单回合 LLM 双调用允许较长等待
})

http.interceptors.request.use(cfg => {
  const token = localStorage.getItem('bs_token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  r => r.config?.rawResponse ? r : r.data,
  err => {
    const sentHeader=err.config?.headers?.get?.('Authorization') ?? err.config?.headers?.Authorization
    const currentToken=localStorage.getItem('bs_token')
    // A late response from an old login must never remove a newer account's token.
    if (err.response?.status === 401 && !err.config?.url?.endsWith('/auth/login') && currentToken && sentHeader===`Bearer ${currentToken}`) {
      localStorage.removeItem('bs_token')
      location.assign('/login')
    }
    return Promise.reject(err)
  }
)

export default http
