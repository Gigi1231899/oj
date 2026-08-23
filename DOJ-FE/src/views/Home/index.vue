<script lang="ts" setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();

// Stats
const stats = ref([
    { label: '累计提交', value: '0', icon: 'upload' },
    { label: '今日提交', value: '0', icon: 'today' },
    { label: '题目总数', value: '0', icon: 'book' },
    { label: '活跃用户', value: '0', icon: 'users' },
]);

import { reqStats } from '@/api/stats';

const formatNumber = (num: number): string => {
    return num.toLocaleString('en-US');
};

const fetchStats = async () => {
    try {
        const res = await reqStats();
        if (res.data.code === 200) {
            const data = res.data.data;
            stats.value = [
                { label: '累计提交', value: formatNumber(data.totalSubmissions), icon: 'upload' },
                { label: '今日提交', value: formatNumber(data.todaySubmissions), icon: 'today' },
                { label: '题目总数', value: formatNumber(data.totalProblems), icon: 'book' },
                { label: '活跃用户', value: formatNumber(data.activeUsers), icon: 'users' },
            ];
        }
    } catch (e) {
        console.error('Failed to fetch stats', e);
    }
};

onMounted(() => {
    fetchStats();
});

const goToProblems = () => {
    router.push('/problem');
};

</script>

<template>
    <div class="home-page">
        <!-- Hero -->
        <section class="hero animate-fade-in-up">
            <div class="hero-content">
                <h1 class="hero-title">
                    <span class="text-gradient">OnlineJudge</span>
                </h1>
                <p class="hero-subtitle">
                    集成 AI 编码助手的现代在线判题平台
                </p>
                <div class="hero-actions">
                    <button class="btn-primary btn-lg" @click="goToProblems">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M4 19.5A2.5 2.5 0 016.5 17H20"/>
                            <path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/>
                        </svg>
                        开始刷题
                    </button>
                </div>
            </div>

            <div class="hero-decoration">
                <div class="floating-card card-1"><span class="status accepted">AC</span></div>
                <div class="floating-card card-2"><span class="code-snippet">&lt;/&gt;</span></div>
                <div class="floating-card card-3"><span class="status runtime">100ms</span></div>
            </div>
        </section>

        <!-- Stats -->
        <section class="stats-section">
            <div class="stats-grid">
                <div v-for="stat in stats" :key="stat.label" class="stat-card card-glass">
                    <div class="stat-icon">
                        <svg v-if="stat.icon === 'upload'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>
                        <svg v-if="stat.icon === 'today'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="18" height="18" rx="2" ry="2"/><line x1="16" y1="2" x2="16" y2="6"/><line x1="8" y1="2" x2="8" y2="6"/><line x1="3" y1="10" x2="21" y2="10"/></svg>
                        <svg v-if="stat.icon === 'book'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 19.5A2.5 2.5 0 016.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 014 19.5v-15A2.5 2.5 0 016.5 2z"/></svg>
                        <svg v-if="stat.icon === 'users'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
                    </div>
                    <div class="stat-info">
                        <span class="stat-value">{{ stat.value }}</span>
                        <span class="stat-label">{{ stat.label }}</span>
                    </div>
                </div>
            </div>
        </section>

        <!-- AI Agent 宣传 -->
        <section class="agent-section">
            <h2 class="section-title">🤖 智能编码助手</h2>
            <p class="section-subtitle">提交代码后一键触发 AI 分析，实时获取优化建议</p>

            <div class="agent-features">
                <div class="agent-card card-glass">
                    <div class="agent-card-icon" style="background: rgba(102, 126, 234, 0.12);">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M14 2H6a2 2 0 00-2 2v16a2 2 0 002 2h12a2 2 0 002-2V8z"/>
                            <polyline points="14 2 14 8 20 8"/>
                            <line x1="16" y1="13" x2="8" y2="13"/>
                            <line x1="16" y1="17" x2="8" y2="17"/>
                        </svg>
                    </div>
                    <h3>代码 Review</h3>
                    <p>自动分析代码正确性、边界条件和潜在 Bug，帮你快速定位问题</p>
                </div>

                <div class="agent-card card-glass">
                    <div class="agent-card-icon" style="background: rgba(245, 158, 11, 0.12);">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/>
                            <polyline points="17 6 23 6 23 12"/>
                        </svg>
                    </div>
                    <h3>性能优化</h3>
                    <p>分析时间复杂度和空间复杂度，给出降低复杂度的具体方案</p>
                </div>

                <div class="agent-card card-glass">
                    <div class="agent-card-icon" style="background: rgba(16, 185, 129, 0.12);">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <path d="M12 20h9"/><path d="M16.5 3.5a2.121 2.121 0 013 3L7 19l-4 1 1-4L16.5 3.5z"/>
                        </svg>
                    </div>
                    <h3>代码补写</h3>
                    <p>根据题意和你的思路，Agent 可以帮你补全或重写正确解法</p>
                </div>

                <div class="agent-card card-glass">
                    <div class="agent-card-icon" style="background: rgba(139, 92, 246, 0.12);">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                            <circle cx="12" cy="12" r="10"/>
                            <path d="M9.09 9a3 3 0 015.83 1c0 2-3 3-3 3"/>
                            <line x1="12" y1="17" x2="12.01" y2="17"/>
                        </svg>
                    </div>
                    <h3>多轮对话</h3>
                    <p>支持追问和深入讨论——"这段能不能改成递归？""为什么要用 HashMap？"</p>
                </div>
            </div>

            <div class="agent-flow">
                <div class="flow-step">
                    <div class="flow-num">1</div>
                    <span>编写代码</span>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-step">
                    <div class="flow-num">2</div>
                    <span>提交判题</span>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-step highlight">
                    <div class="flow-num">3</div>
                    <span>AI 分析</span>
                </div>
                <div class="flow-arrow">→</div>
                <div class="flow-step">
                    <div class="flow-num">4</div>
                    <span>迭代优化</span>
                </div>
            </div>
        </section>
    </div>
</template>

<style scoped lang="scss">
.home-page {
    display: flex;
    flex-direction: column;
    gap: $space-2xl;
}

// ─── Hero ──────────────────────────────────────────────────────
.hero {
    position: relative;
    text-align: center;
    padding: $space-3xl 0;

    .hero-content { position: relative; z-index: 1; }

    .hero-title {
        font-size: clamp($font-size-4xl, 8vw, 72px);
        font-weight: $font-weight-bold;
        margin-bottom: $space-md;
        line-height: 1.1;
    }

    .hero-subtitle {
        font-size: $font-size-xl;
        color: var(--text-secondary);
        margin-bottom: $space-xl;
        max-width: 500px;
        margin-left: auto;
        margin-right: auto;
    }

    .hero-actions {
        display: flex;
        gap: $space-md;
        justify-content: center;
        flex-wrap: wrap;

        .btn-lg {
            padding: $space-md $space-xl;
            font-size: $font-size-base;
            display: flex;
            align-items: center;
            gap: $space-sm;
            svg { width: 20px; height: 20px; }
        }
    }
}

.hero-decoration {
    position: absolute;
    top: 0; left: 0; right: 0; bottom: 0;
    pointer-events: none;
    overflow: hidden;
}

.floating-card {
    position: absolute;
    padding: $space-sm $space-md;
    background: var(--glass-bg);
    backdrop-filter: blur(8px);
    border: 1px solid var(--glass-border);
    border-radius: $radius-md;
    font-weight: $font-weight-semibold;
    animation: float 6s ease-in-out infinite;

    &.card-1 { top: 20%; left: 10%; animation-delay: 0s; .accepted { color: $success; } }
    &.card-2 { top: 30%; right: 15%; animation-delay: 2s; .code-snippet { @include gradient-text; font-family: $font-mono; } }
    &.card-3 { bottom: 25%; left: 20%; animation-delay: 4s; .runtime { color: $info; } }
}

@keyframes float {
    0%, 100% { transform: translateY(0); }
    50% { transform: translateY(-20px); }
}

// ─── Stats ─────────────────────────────────────────────────────
.stats-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: $space-lg;
    @include mobile { grid-template-columns: repeat(2, 1fr); }
}

.stat-card {
    display: flex;
    align-items: center;
    gap: $space-md;
    padding: $space-lg;
    @include hover-glow;

    .stat-icon {
        width: 48px; height: 48px;
        display: flex; align-items: center; justify-content: center;
        background: var(--primary-gradient);
        border-radius: $radius-md;
        svg { width: 24px; height: 24px; color: white; }
    }

    .stat-info { display: flex; flex-direction: column; }

    .stat-value {
        font-size: $font-size-2xl;
        font-weight: $font-weight-bold;
        color: var(--text-primary);
    }

    .stat-label {
        font-size: $font-size-sm;
        color: var(--text-muted);
    }
}

// ─── Agent 宣传 ────────────────────────────────────────────────
.agent-section {
    text-align: center;

    .section-title {
        font-size: $font-size-3xl;
        font-weight: $font-weight-bold;
        margin-bottom: $space-sm;
    }

    .section-subtitle {
        color: var(--text-secondary);
        font-size: $font-size-lg;
        margin-bottom: $space-xl;
    }
}

.agent-features {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: $space-lg;
    margin-bottom: $space-2xl;

    @include mobile { grid-template-columns: repeat(2, 1fr); }
}

.agent-card {
    padding: $space-xl;
    text-align: center;
    @include hover-glow;

    .agent-card-icon {
        width: 56px; height: 56px;
        margin: 0 auto $space-md;
        display: flex; align-items: center; justify-content: center;
        border-radius: $radius-lg;
        svg { width: 28px; height: 28px; color: var(--primary-start); }
    }

    h3 {
        font-size: $font-size-base;
        font-weight: $font-weight-semibold;
        margin-bottom: $space-sm;
    }

    p {
        font-size: $font-size-sm;
        color: var(--text-secondary);
        line-height: 1.6;
        margin: 0;
    }
}

// ─── 使用流程 ──────────────────────────────────────────────────
.agent-flow {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: $space-lg;
    padding: $space-xl;
    background: var(--glass-bg);
    border-radius: $radius-lg;
    flex-wrap: wrap;

    .flow-step {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: $space-xs;
        font-size: $font-size-sm;
        color: var(--text-secondary);

        &.highlight .flow-num {
            background: var(--primary-gradient);
            color: #fff;
            box-shadow: 0 0 12px rgba(102, 126, 234, 0.4);
        }
        &.highlight span { color: var(--primary-start); font-weight: $font-weight-semibold; }
    }

    .flow-num {
        width: 36px; height: 36px;
        display: flex; align-items: center; justify-content: center;
        border-radius: $radius-full;
        background: var(--bg-elevated);
        border: 1px solid var(--border-color);
        font-weight: $font-weight-bold;
    }

    .flow-arrow {
        font-size: $font-size-xl;
        color: var(--text-disabled);
        @include mobile { display: none; }
    }
}
</style>
