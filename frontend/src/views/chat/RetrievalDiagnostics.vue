<script setup lang="ts">
import type { Metric } from "../../types/chat.js";
defineProps<{ metric: Metric }>();
const modes: Record<string, string> = {
  "hybrid-rrf": "BM25 + 向量 · RRF 融合",
  agent: "Agent 按需检索与工具调用",
  dense: "向量检索",
  bm25: "本地 BM25",
  direct: "模型日常回应（跳过检索）",
  clarification: "模型理解与澄清（跳过检索）",
  conversation: "结合历史回应（跳过检索）",
  safety: "安全支持回复（跳过检索）",
};
const checks = {
  VALID: "引用编号有效",
  MISSING: "未标注来源（不代表一定漏引）",
  INVALID: "存在无效引用",
  NOT_REQUIRED: "本轮无参考资料",
};
</script>
<template>
  <details>
    <summary>本轮检索与上下文</summary>
    <div class="diagnostics">
      <p>检索方式：{{ modes[metric.retrievalMode ?? ""] ?? "旧记录未保存" }}</p>
      <p>{{ metric.retrievalMode === "agent" ? "本轮问题" : "改写查询" }}：{{ metric.rewrittenQuery }}</p>
      <p>
        检索 {{ metric.retrievedCount }} 篇 · 注入上下文
        {{ metric.contextIds?.length ?? "未记录" }} 篇 · 首段耗时
        {{ metric.firstTokenMs }} ms
      </p>
      <p>输入预算估算：{{ metric.contextEstimate }}（UTF-8 字节保守估算）</p>
      <p>
        {{ metric.retrievalMode === "agent" ? "Agent 各步累计 Token" : "回答 Token" }}：{{ metric.promptTokens ?? "未提供" }} 输入 /
        {{ metric.completionTokens ?? "未提供" }} 输出
      </p>
      <p>
        摘要版本：{{ metric.summaryVersion }} · 覆盖消息序号：{{
          metric.coveredThroughSeq
        }}
      </p>
      <p>
        引用检查：{{
          metric.citationCheck
            ? checks[metric.citationCheck.status]
            : "旧记录未校验"
        }}。编号有效不代表原文支持所有结论。
      </p>
      <p v-if="metric.citationCheck?.invalidIds.length">
        无法对应：{{ metric.citationCheck.invalidIds.join("、") }}
      </p>
      <div v-if="metric.retrievalMatches?.length" class="ranking-scroll">
        <table class="ranking-table">
          <caption>
            召回排名（— 表示该通道未命中）
          </caption>
          <thead>
            <tr>
              <th>片段</th>
              <th>向量</th>
              <th>BM25</th>
              <th>RRF</th>
              <th>上下文</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="m in metric.retrievalMatches" :key="m.chunkId">
              <th>{{ m.chunkId }}</th>
              <td
                :title="
                  m.vectorScore == null ? '' : `向量得分 ${m.vectorScore}`
                "
              >
                {{ m.vectorRank == null ? "—" : "#" + m.vectorRank }}
              </td>
              <td
                :title="
                  m.lexicalScore == null ? '' : `BM25 得分 ${m.lexicalScore}`
                "
              >
                {{ m.lexicalRank == null ? "—" : "#" + m.lexicalRank }}
              </td>
              <td>{{ m.rrfScore?.toFixed(5) ?? "—" }}</td>
              <td>
                {{
                  metric.contextIds?.includes(m.chunkId)
                    ? "已纳入"
                    : "预算未纳入"
                }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-if="metric.retrievalMode === 'hybrid-rrf' && metric.retrievalConfig">
        每路最多 {{ metric.retrievalConfig.candidateLimit }} 个候选，RRF k={{
          metric.retrievalConfig.rrfK
        }}。融合分数只用于排序，不是相似度或可信度。
      </p>
    </div>
  </details>
</template>
<style scoped>
.ranking-scroll {
  overflow-x: auto;
}
.ranking-table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
}
.ranking-table th,
.ranking-table td {
  padding: 8px 6px;
  border-bottom: 1px solid #e8e0ed;
  font-weight: 400;
}
.ranking-table tbody th {
  max-width: 160px;
  overflow-wrap: anywhere;
}
.ranking-table caption {
  text-align: left;
  padding: 8px 0;
}
</style>
