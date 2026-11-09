package com.bs.narrative.web;

import com.bs.narrative.exception.*;
import com.bs.narrative.service.AuthService;
import com.bs.narrative.llm.LLMUsage;
import com.bs.narrative.llm.MainModelContext;
import com.bs.narrative.service.MainModelService;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.ServletRequestPathUtils;
import java.io.*;
import java.time.Instant;
import java.util.*;

/** Authentication and ownership are checked before controllers, including every rewind/read endpoint. */
@Component
public class GameAccessFilter extends OncePerRequestFilter {
    private final AuthService auth;
    private final MainModelService models;
    private final ObjectMapper mapper=new ObjectMapper();
    public GameAccessFilter(AuthService auth,MainModelService models) {this.auth=auth;this.models=models;}
    @Override protected boolean shouldNotFilter(HttpServletRequest req) {
        String p=path(req);
        return req.getMethod().equals("OPTIONS") || !p.startsWith("/api/") || (p.equals("/api/auth/login") || p.equals("/api/auth/register"));
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        HttpServletRequest checked;String selectedModel=null;
        try {
            String header=req.getHeader("Authorization");
            String pid=auth.authenticate(header!=null && header.startsWith("Bearer ")?header.substring(7):null);
            // A 4000-character dialogue is carried twice (playerText/spokenText).
            // JSON Unicode escapes can make that valid payload about 48 KB.
            int bodyLimit=Set.of("/api/game/operations","/api/game/action").contains(path(req))?65536:16384;
            byte[] body=req.getInputStream().readNBytes(bodyLimit+1);
            if(body.length>bodyLimit) throw new BusinessException(ErrorCode.PARAM_RANGE_INVALID,"请求内容过长");
            ObjectNode object=null;
            if(body.length>0) {
                JsonNode parsed=mapper.readTree(body);
                if(!parsed.isObject()) throw new BusinessException(ErrorCode.PARAM_INVALID,"请求体必须为 JSON 对象");
                object=(ObjectNode)parsed;
            }
            String supplied=req.getParameter("playerId");
            if(object!=null && object.hasNonNull("playerId")) {
                if(supplied!=null && !supplied.equals(object.path("playerId").asText()))
                    throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,"玩家身份不一致");
                supplied=object.path("playerId").asText();
            }
            if(supplied!=null && !pid.equals(supplied)) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,"不能使用其他玩家身份");
            if(path(req).startsWith("/api/game/")) {
                String sid=req.getParameter("sessionId");
                if(object!=null && object.hasNonNull("sessionId")) {
                    String bodySid=object.path("sessionId").asText();
                    if(sid!=null && !sid.equals(bodySid)) throw new BusinessException(ErrorCode.PARAM_INVALID,"会话参数不一致");
                    sid=bodySid;
                }
                boolean needsSession=!Set.of("/api/game/start","/api/game/sessions","/api/game/restart").contains(path(req));
                if(needsSession && (sid==null || sid.isBlank())) throw new BusinessException(ErrorCode.PARAM_MISSING,"缺少会话标识");
                if(sid!=null){auth.requireOwnedSession(sid,pid);selectedModel=models.selected(sid);}
                if(object!=null && object.hasNonNull("prevSessionId")) auth.requireOwnedSession(object.path("prevSessionId").asText(),pid);
            }
            if(object!=null) {object.put("playerId",pid);body=mapper.writeValueAsBytes(object);}
            req.setAttribute("authenticatedPlayerId",pid);
            checked=new AccountRequest(req,body,pid);
        } catch(BusinessException e) {
            int status=e.getErrorCode()==ErrorCode.AUTH_FORBIDDEN?403:
                    (e.getErrorCode()==ErrorCode.AUTH_REQUIRED?401:400);
            error(res,status,e.getCode(),e.getMessage());return;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {
            error(res,400,100002,"JSON 格式错误");return;
        }
        try(var modelScope=MainModelContext.use(selectedModel)){chain.doFilter(checked,res);} finally {LLMUsage.clear();}
    }
    private void error(HttpServletResponse res,int status,int code,String message) throws IOException {
        res.setStatus(status);res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(),Map.of("code",code,"message",message,"traceId",UUID.randomUUID().toString(),"timestamp",Instant.now().toString()));
    }
    private String path(HttpServletRequest req) {
        // Match the same decoded segments as Spring MVC. Raw requestURI retains
        // matrix parameters and escapes, so it must not decide security scope.
        var parsed=ServletRequestPathUtils.hasParsedRequestPath(req)
                ?ServletRequestPathUtils.getParsedRequestPath(req)
                :ServletRequestPathUtils.parseAndCache(req);
        var canonical=new StringBuilder();
        for(var part:parsed.pathWithinApplication().elements())
            canonical.append(part instanceof PathContainer.PathSegment segment?segment.valueToMatch():part.value());
        return canonical.toString();
    }
    private static final class AccountRequest extends HttpServletRequestWrapper {
        private final byte[] body;private final String pid;
        AccountRequest(HttpServletRequest req,byte[] body,String pid) {super(req);this.body=body;this.pid=pid;}
        @Override public String getParameter(String name) {return name.equals("playerId")?pid:super.getParameter(name);}
        @Override public String[] getParameterValues(String name) {return name.equals("playerId")?new String[]{pid}:super.getParameterValues(name);}
        @Override public Map<String,String[]> getParameterMap() {var m=new HashMap<>(super.getParameterMap());m.put("playerId",new String[]{pid});return Collections.unmodifiableMap(m);}
        @Override public int getContentLength() {return body.length;}
        @Override public long getContentLengthLong() {return body.length;}
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream bytes=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                public int read(){return bytes.read();} public boolean isFinished(){return bytes.available()==0;}
                public boolean isReady(){return true;} public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}
            };
        }
        @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
    }
}
