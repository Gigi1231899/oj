<template>
    <div class="editor">
        <div class="main">
            <codemirror v-model="code" :style="{
                width: config.width,
                height: config.height,
                backgroundColor: 'var(--bg-primary)',
                color: 'var(--text-primary)',
                fontSize: fontSizeStr,
            }" placeholder="Please enter the code." :extensions="extensions" :disabled="config.disabled"
                :indent-with-tab="true" :tab-size="config.tabSize" @update="handleStateUpdate" @ready="handleReady" />
        </div>
        <div class="divider"></div>
        <div class="footer">
            <div class="buttons">
                <el-button :icon="VideoPlay" type="primary" round :loading="submitting" :disabled="submitting" @click="handleSubmit1">submit</el-button>
                <el-button v-if="judgeResult" type="warning" round @click="goAssistant">编码助手分析</el-button>
            </div>
            <div class="infos">
                <span class="item">Spaces: {{ config.tabSize }}</span>
                <span class="item">Length: {{ state.length }}</span>
                <span class="item">Lines: {{ state.lines }}</span>
                <span class="item">Cursor: {{ state.cursor }}</span>
                <span class="item">Selected: {{ state.selected }}</span>
            </div>
        </div>
        <div v-if="judgeResult" class="result-panel" :class="{ accepted: judgeResult.status === 'Accepted', error: judgeResult.status !== 'Accepted' }">
            <div class="result-header">
                <span class="result-status">{{ judgeResult.status }}</span>
                <span v-if="judgeResult.time != null" class="result-stat">耗时: {{ (judgeResult.time * 1000).toFixed(2) }} ms</span>
                <span v-if="judgeResult.memory != null" class="result-stat">内存: {{ judgeResult.memory.toFixed(2) }} KB</span>
            </div>
            <pre v-if="judgeResult.message && judgeResult.status !== 'Accepted'" class="result-message">{{ judgeResult.message }}</pre>
        </div>

    </div>
</template>

<script lang="ts" setup>
import { reactive, shallowRef, computed, watch, onMounted, ref } from 'vue'
import { EditorView, ViewUpdate } from '@codemirror/view'
import { Codemirror } from 'vue-codemirror'
import { ElButton, ElMessage } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { configType } from './index.vue'
import { useRoute, useRouter } from 'vue-router'
import { reqProblemValidate } from '@/api/submit'
import { useWebSocket } from '@/utils/websocket'

const props = defineProps<{
    config: configType,
    code: string,
    theme: Object | Array<string>,
    language: Function,
    languageName: string
}>();

const route = useRoute();
const router = useRouter();

defineExpose({
    Codemirror
});

const code = shallowRef(props.code);
const cmView = shallowRef<EditorView>();

const fontSizeStr = computed(() => `${props.config.fontSize}px`);

const extensions = computed(() => {
    const result = [];
    if (props.language) {
        result.push(props.language());
    }
    if (props.theme) {
        result.push(props.theme);
    }
    return result;
});

const handleReady = ({ view }: any) => {
    cmView.value = view;
};

const state = reactive({
    lines: null as null | number,
    cursor: null as null | number,
    selected: null as null | number,
    length: null as null | number
});

const handleStateUpdate = (viewUpdate: ViewUpdate) => {
    const ranges = viewUpdate.state.selection.ranges
    state.selected = ranges.reduce((plus, range) => plus + range.to - range.from, 0)
    state.cursor = ranges[0].anchor
    state.length = viewUpdate.state.doc.length
    state.lines = viewUpdate.state.doc.lines
};

const judgeResult = ref<any>(null);
const submitting = ref(false);

const goAssistant = () => {
    const sid = judgeResult.value?.id;
    window.open(`/problem/${route.params.id}/assistant${sid ? `?submissionId=${sid}` : ''}`, '_blank');
};

const handleSubmit1 = async () => {
    judgeResult.value = null;
    submitting.value = true;

    const codeBlob = new Blob([code.value], { type: 'text/plain' });
    const languageExtension = getLanguageExtension(props.languageName);

    const formData = new FormData();
    const pid = route.params.id as string;
    formData.append('pid', pid);
    formData.append('file', codeBlob, `Main.${languageExtension}`);
    formData.append('language', props.languageName);

    try {
        const response = (await reqProblemValidate(formData)).data;
        if (response.code === 200) {
            ElMessage.info('提交成功，判题中...');
            const submissionId = response.data;
            useWebSocket().subscribeToSubmission(submissionId, (result) => {
                judgeResult.value = result;
                submitting.value = false;
            });
        } else {
            ElMessage.error(`提交失败: ${response.message}`);
            submitting.value = false;
        }
    } catch (err) {
        ElMessage.error('提交请求失败');
        submitting.value = false;
    }
};

const getLanguageExtension = (languageName: string) => {
    if (!languageName) return 'txt';
    const language = languageName.toLowerCase();
    switch (language) {
        case 'javascript': return 'js';
        case 'python': return 'py';
        case 'cpp': return 'cpp';
        case 'java': return 'java';
        default: return 'txt';
    }
};

// 路由变化时重置代码为模板
watch(
    () => props.code,
    (_code) => {
        code.value = _code;
    },
    { immediate: true }
);

onMounted(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
        const isSaveShortcut =
            (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's';
        if (isSaveShortcut) {
            event.preventDefault();
            console.log('Save shortcut (Ctrl+S / Cmd+S) is disabled in the editor.');
        }
    };
    window.addEventListener('keydown', handleKeyDown);
});

const log = console.log
</script>

<style lang="scss" scoped>
@import '@/styles/variable.scss';

.editor {
    .divider {
        height: 1px;
        background-color: $border-color;
    }

    .main {

        .code {
            width: 30%;
            height: 100px;
            margin: 0;
            padding: 0.4em;
            overflow: scroll;
            border-left: 1px solid $border-color;
            font-family: monospace;
        }
    }

    .footer {
        height: 3rem;
        padding: 0 1em;
        display: flex;
        justify-content: space-between;
        align-items: center;
        font-size: 90%;
        background-color: var(--bg-elevated);
        border-top: 1px solid var(--border-color);

        .buttons {
            .item {
                margin-right: 1em;
                display: inline-flex;
                justify-content: center;
                align-items: center;
                background-color: transparent;
                border: 1px dashed var(--border-color);
                font-size: $font-size-small;
                color: var(--text-secondary);
                cursor: pointer;

                .iconfont {
                    margin-left: $xs-gap;
                }

                &:hover {
                    color: var(--text-primary);
                    border-color: var(--text-primary);
                }
            }

            :deep(.el-button) {
                background: var(--bg-primary);
                border-color: var(--border-color);
                color: var(--text-primary);

                &:hover, &:focus {
                    background: var(--primary-start);
                    border-color: var(--primary-start);
                    color: #fff;
                }

                &.el-button--primary {
                    background: var(--primary-gradient);
                    border: none;
                    color: #fff;

                    &:hover {
                        opacity: 0.9;
                    }
                }
            }
        }

        .infos {
            color: var(--text-secondary);
            .item {
                margin-left: 2em;
                display: inline-block;
                font-feature-settings: 'tnum';
            }
        }
    }

    .result-panel {
        padding: 0.75em 1em;
        font-size: 90%;
        border-top: 1px solid var(--border-color);

        &.accepted {
            background: rgba(103, 194, 58, 0.06);
            .result-status { color: #67c23a; }
        }
        &.error {
            background: rgba(245, 108, 108, 0.06);
            .result-status { color: #f56c6c; }
        }

        .result-header {
            display: flex;
            align-items: center;
            gap: 1.5em;
            .result-status {
                font-weight: bold;
            }
            .result-stat {
                color: var(--text-secondary);
            }
        }

        .result-message {
            margin: 0.5em 0 0 0;
            padding: 0.6em;
            background: var(--bg-primary);
            border: 1px solid var(--border-color);
            border-radius: 4px;
            white-space: pre-wrap;
            word-break: break-all;
            font-family: monospace;
            font-size: 90%;
            max-height: 20em;
            overflow-y: auto;
        }
    }

}
</style>
