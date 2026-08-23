<script lang="ts" setup>
const currentYear = new Date().getFullYear();
</script>

<template>
    <footer class="footer">
        <div class="footer-inner">
            <div class="footer-content">
                <!-- Logo and Description -->
                <div class="footer-brand">
                    <div class="brand-logo">
                        <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="url(#footerGradient)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M2 17L12 22L22 17" stroke="url(#footerGradient)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                            <path d="M2 12L12 17L22 12" stroke="url(#footerGradient)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
                            <defs>
                                <linearGradient id="footerGradient" x1="2" y1="2" x2="22" y2="22">
                                    <stop stop-color="#667eea"/>
                                    <stop offset="1" stop-color="#764ba2"/>
                                </linearGradient>
                            </defs>
                        </svg>
                        <span>OnlineJudge</span>
                    </div>
                    <p class="brand-desc">OnlineJudge — 集成 AI 编码助手的在线判题平台</p>
                </div>
            </div>

        </div>
    </footer>
</template>

<style scoped lang="scss">


.footer {
    margin-top: auto;
    border-top: 1px solid var(--border-color);
    background: var(--bg-secondary);
}

.footer-inner {
    max-width: $container-max;
    margin: 0 auto;
    padding: $space-xl $container-padding;
}

.footer-content {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: $space-lg;
    
    @include mobile {
        flex-direction: column;
        gap: $space-lg;
        text-align: center;
    }
}

.footer-brand {
    .brand-logo {
        display: flex;
        align-items: center;
        gap: $space-sm;
        margin-bottom: $space-sm;
        
        svg {
            width: 24px;
            height: 24px;
        }
        
        span {
            font-size: $font-size-lg;
            font-weight: $font-weight-bold;
            @include gradient-text;
        }
    }
    
    .brand-desc {
        color: var(--text-muted);
        font-size: $font-size-sm;
    }
}

.footer-links {
    display: flex;
    gap: $space-md;
}

.footer-link {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border-radius: $radius-full;
    background: var(--glass-bg);
    color: var(--text-secondary);
    transition: all $transition-fast;
    
    svg {
        width: 20px;
        height: 20px;
    }
    
    &:hover {
        background: var(--primary-gradient);
        color: white;
        transform: translateY(-2px);
    }
}

</style>