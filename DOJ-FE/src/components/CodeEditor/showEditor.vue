<template>
    <div class="editor">
        <div class="main">
            <codemirror v-model="code" :style="{
                width: config.width,
                height: config.height,
                backgroundColor: 'var(--bg-primary)',
                color: 'var(--text-primary)',
                fontSize: fontSizeStr,
            }" placeholder="Please enter the code." :extensions="extensions" :disabled="true"
                :indent-with-tab="true" :tab-size="config.tabSize" @update="handleStateUpdate" @ready="handleReady" />
        </div>
        <div class="divider"></div>
        <div class="footer">
            <div class="infos">
                <span class="item">Spaces: {{ config.tabSize }}</span>
                <span class="item">Length: {{ state.length }}</span>
                <span class="item">Lines: {{ state.lines }}</span>
                <span class="item">Cursor: {{ state.cursor }}</span>
                <span class="item">Selected: {{ state.selected }}</span>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { reactive, shallowRef, computed, watch, onMounted } from 'vue'
import { EditorView, ViewUpdate } from '@codemirror/view'
import { Codemirror } from 'vue-codemirror'
import { configType } from './index.vue'

const props = defineProps<{
    config: configType,
    code: string,
    theme: Object | Array<string>,
    language: Function,
    languageName: string,
    problemId?: number
}>();

// 组件暴露
defineExpose({
    Codemirror
});

// 响应式状态
const code = shallowRef(props.code);
const cmView = shallowRef<EditorView>();

const fontSizeStr = computed(() => `${props.config.fontSize}px`);

// 计算属性
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

// 路由变化时重置代码为模板
watch(
    () => props.code,
    (_code) => {
        code.value = _code
    },
    { immediate: true }
);

onMounted(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
        const isSaveShortcut =
            (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's';

        if (isSaveShortcut) {
            event.preventDefault(); // 阻止默认保存行为
            console.log('Save shortcut (Ctrl+S / Cmd+S) is disabled in the editor.');
            // 可选：在这里执行其他逻辑，比如保存代码等
        }
    };

    window.addEventListener('keydown', handleKeyDown);
});
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
}
</style>
