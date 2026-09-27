package com.guide.service.shortlink;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guide.dao.entity.SceneLinkDO;
import com.guide.dao.mapper.SceneLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 短链业务服务：景点短链映射的匹配、创建与复用。
 *
 * 设计要点：
 * - 短码持久化在 scene_link.short_code：只创建一次并跨会话复用，点击统计才能按景点持续聚合
 * - 短链平台不可用时降级直接展示长链（并标记 degraded），主流程（问答+语音）完全不受影响
 *   —— 把短链平台自身的「熔断降级」思想反向应用到了调用方
 */
@Service
public class ShortLinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortLinkService.class);

    private final SceneLinkMapper sceneLinkMapper;
    private final ShortLinkClient shortLinkClient;

    @Value("${guide.shortlink.expire-days:3650}")
    private int expireDays;

    /** 短链平台对外域名：拼接完整可点击短链 */
    @Value("${guide.shortlink.domain:http://localhost:8080}")
    private String shortLinkDomain;

    public ShortLinkService(SceneLinkMapper sceneLinkMapper, ShortLinkClient shortLinkClient) {
        this.sceneLinkMapper = sceneLinkMapper;
        this.shortLinkClient = shortLinkClient;
    }

    /** 下发给前端的链接项：shortUrl 为空表示降级展示长链 */
    public record LinkItem(String scene, String shortUrl, String longUrl, boolean degraded) {
    }

    /**
     * 匹配问答中命中的景点并构建链接列表。
     * 匹配范围：游客问题 + AI 回答（回答中出现景点名也值得下发入口）。
     */
    public List<LinkItem> buildLinksFor(String question, String answer) {
        List<SceneLinkDO> scenes = sceneLinkMapper.selectList(
                new LambdaQueryWrapper<SceneLinkDO>().eq(SceneLinkDO::getEnabled, 1));
        if (scenes.isEmpty()) {
            return List.of();
        }
        String text = (question == null ? "" : question) + "\n" + (answer == null ? "" : answer);

        List<LinkItem> links = new ArrayList<>();
        for (SceneLinkDO scene : scenes) {
            if (!hitKeyword(scene.getKeyword(), text)) {
                continue;
            }
            links.add(buildLink(scene));
        }
        return links;
    }

    /**
     * 获取或创建景点短链：
     * 已有 shortCode → 直接用（统计持续聚合）；
     * 没有 → 调短链平台创建（customCode=g{id}，语义可读且稳定），回填持久化；
     * 创建失败 → 降级长链，degraded=true。
     */
    private LinkItem buildLink(SceneLinkDO scene) {
        String shortCode = scene.getShortCode();
        if (shortCode != null && !shortCode.isBlank()) {
            return new LinkItem(scene.getSceneName(), shortUrlOf(shortCode), scene.getLongUrl(), false);
        }
        try {
            Map<String, String> created = shortLinkClient.createLink(
                    scene.getLongUrl(), "g" + scene.getId(), expireDays);
            shortCode = created.get("shortCode");
            scene.setShortCode(shortCode);
            sceneLinkMapper.updateById(scene);
            return new LinkItem(scene.getSceneName(), shortUrlOf(shortCode), scene.getLongUrl(), false);
        } catch (Exception e) {
            log.warn("景点[{}]短链创建失败，降级展示长链: {}", scene.getSceneName(), e.getMessage());
            return new LinkItem(scene.getSceneName(), "", scene.getLongUrl(), true);
        }
    }

    /** 各景点短链点击统计（漏斗「推荐→点击」环节），单景点失败跳过不影响整体 */
    public record SceneStats(String scene, String shortUrl, long pv, long uv, long todayPv) {
    }

    public List<SceneStats> sceneStats() {
        List<SceneLinkDO> scenes = sceneLinkMapper.selectList(
                new LambdaQueryWrapper<SceneLinkDO>().isNotNull(SceneLinkDO::getShortCode));
        List<SceneStats> result = new ArrayList<>();
        for (SceneLinkDO scene : scenes) {
            try {
                Map<String, Long> stats = shortLinkClient.getStats(scene.getShortCode());
                result.add(new SceneStats(scene.getSceneName(), shortUrlOf(scene.getShortCode()),
                        stats.get("pv"), stats.get("uv"), stats.get("todayPv")));
            } catch (Exception e) {
                log.warn("景点[{}]统计查询失败: {}", scene.getSceneName(), e.getMessage());
            }
        }
        return result;
    }

    /** 长链接变更后调用：清空旧短码，下次命中时重建（旧短链在平台上自然过期） */
    public void resetShortCode(SceneLinkDO scene) {
        scene.setShortCode(null);
        sceneLinkMapper.updateById(scene);
    }

    private static boolean hitKeyword(String keywordConfig, String text) {
        if (keywordConfig == null || keywordConfig.isBlank()) {
            return false;
        }
        for (String kw : keywordConfig.split("[,，]")) {
            String k = kw.trim();
            if (!k.isEmpty() && text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String shortUrlOf(String shortCode) {
        return shortLinkDomain + "/" + shortCode;
    }
}
