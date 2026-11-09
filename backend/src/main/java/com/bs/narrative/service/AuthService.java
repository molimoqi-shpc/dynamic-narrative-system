package com.bs.narrative.service;

import com.bs.narrative.exception.*;
import com.bs.narrative.repository.PlayerProfileRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.SecretKey;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

/** BCrypt credentials, persisted signed-token revocation, and server-owned account identity. */
@Service
public class AuthService {
    private final PlayerProfileRepository players;
    private final JdbcTemplate jdbc;
    private final SecretKey key;
    private final long expirationMinutes;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final String dummyHash=encoder.encode(UUID.randomUUID().toString());

    public AuthService(PlayerProfileRepository players,JdbcTemplate jdbc,
            @Value("${auth.key-file:${user.dir}/.runtime/jwt-signing.key}") String keyFile,
            @Value("${jwt.expiration-minutes:720}") long expirationMinutes) {
        this.players=players;this.jdbc=jdbc;this.expirationMinutes=expirationMinutes;
        try {
            Path path=Path.of(keyFile);Files.createDirectories(path.toAbsolutePath().getParent());
            Path legacy=Path.of("D:/CODEX/Codex工作室/私密配置/jwt-signing.key");
            if(!Files.exists(path) && Files.isRegularFile(legacy)) {
                try{Files.copy(legacy,path);}catch(FileAlreadyExistsException ignored){ /* Other instance migrated it. */ }
            }
            if(!Files.exists(path)) {
                byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
                try {Files.writeString(path,Base64.getEncoder().encodeToString(bytes),StandardOpenOption.CREATE_NEW);}
                catch(FileAlreadyExistsException ignored) { /* Another app instance initialized the key. */ }
            }
            key=Keys.hmacShaKeyFor(Base64.getDecoder().decode(Files.readString(path).trim()));
        } catch(Exception e) {throw new IllegalStateException("无法初始化本地登录签名配置",e);}
    }

    @Transactional
    public Map<String,Object> login(String username,String password) {
        if(password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new BusinessException(ErrorCode.PARAM_RANGE_INVALID,"密码长度超过支持范围");
        var player=players.findByUsername(username.trim());
        String hash=player.map(p->p.getPasswordHash()).orElse(dummyHash);
        if(!encoder.matches(password,hash) || player.isEmpty())
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS,"账号或密码错误");
        var account=player.get();Instant expires=Instant.now().plusSeconds(expirationMinutes*60);
        String token=Jwts.builder().issuer("bs-narrative").subject(account.getPlayerId())
                .id(UUID.randomUUID().toString()).issuedAt(new Date()).expiration(Date.from(expires)).signWith(key).compact();
        jdbc.update("DELETE FROM auth_token WHERE expires_at<=UTC_TIMESTAMP(6)");
        jdbc.update("INSERT INTO auth_token(token_hash,player_id,expires_at) VALUES(?,?,?)",
                tokenHash(token),account.getPlayerId(),LocalDateTime.ofInstant(expires,ZoneOffset.UTC));
        return Map.of("token",token,"playerId",account.getPlayerId(),"username",account.getUsername(),"expiresAt",expires.toString());
    }

    @Transactional
    public Map<String,Object> register(String username,String password) {
        if(username==null || !username.matches("[A-Za-z0-9_]{3,30}"))
            throw new BusinessException(ErrorCode.PARAM_INVALID,"账号需为3至30位字母、数字或下划线");
        if(password==null || password.length()<8 || password.getBytes(StandardCharsets.UTF_8).length>72)
            throw new BusinessException(ErrorCode.PARAM_RANGE_INVALID,"密码至少8位，UTF-8编码长度不超过72字节");
        if(players.findByUsername(username).isPresent())
            throw new BusinessException(ErrorCode.DATA_CONFLICT,"这个账号已被使用，请换一个");
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,28);
        String pid="usr_"+suffix, god="new_"+suffix, scene="pre_"+suffix, anchor="acc_"+suffix;
        // Private pre-birth placeholders satisfy existing foreign keys; never become global NPC templates.
        try {
            jdbc.update("INSERT INTO god_profile(god_id,session_id,god_name,god_status,god_emotion,base_ben,base_gui,inventory) VALUES(?,?,'未入世者','MORTAL','平静',0,75,'[]')",god,anchor);
            jdbc.update("INSERT INTO game_scene(scene_id,session_id,location_name,present_gods,scene_summary) VALUES(?,?,'未入世','[]','')",scene,anchor);
            jdbc.update("INSERT INTO player_profile(player_id,god_id,username,password_hash,current_scene_id,inventory,view_mode) VALUES(?,?,?,?,?,'[]','DREAMER')",pid,god,username,encoder.encode(password),scene);
        } catch(org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.DATA_CONFLICT,"这个账号已被使用，请换一个");
        }
        return Map.of("registered",true,"username",username);
    }

    public String authenticate(String token) {
        if(token==null || token.length()>4096) throw new BusinessException(ErrorCode.AUTH_REQUIRED,"请先登录");
        String pid;
        try {pid=Jwts.parser().verifyWith(key).requireIssuer("bs-narrative").build().parseSignedClaims(token).getPayload().getSubject();}
        catch(Exception e) {throw new BusinessException(ErrorCode.AUTH_REQUIRED,"登录已失效，请重新登录");}
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM auth_token WHERE token_hash=? AND player_id=? AND expires_at>UTC_TIMESTAMP(6)",Integer.class,tokenHash(token),pid);
        if(n==0 || players.findById(pid).isEmpty()) throw new BusinessException(ErrorCode.AUTH_REQUIRED,"登录已失效，请重新登录");
        return pid;
    }

    public Map<String,Object> account(String pid) {
        var p=players.findById(pid).orElseThrow();return Map.of("playerId",pid,"username",p.getUsername());
    }

    public void requireOwnedSession(String sid,String pid) {
        Integer n=jdbc.queryForObject("SELECT COUNT(*) FROM game_session WHERE session_id=? AND player_id=?",Integer.class,sid,pid);
        if(n==0) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN,"无权访问该会话");
    }

    @Transactional
    public void logout(String token) {jdbc.update("DELETE FROM auth_token WHERE token_hash=?",tokenHash(token));}

    private String tokenHash(String token) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
}
