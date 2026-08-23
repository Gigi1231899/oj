<template>
  <div class="problem-form" v-if="visible">
    <div class="form-header">
      <div class="form-title">{{ isEdit ? "编辑题目" : "新增题目" }}</div>
      <el-button type="text" icon="el-icon-close" @click="visible = false" />
    </div>
    <el-form :model="form" label-width="100px">
      <el-form-item label="标题">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="难度">
        <el-select v-model="form.difficulty">
          <el-option label="简单" value="简单" />
          <el-option label="中等" value="中等" />
          <el-option label="困难" value="困难" />
        </el-select>
      </el-form-item>
      <el-form-item label="时间限制">
        <el-input v-model="form.timeLimit" placeholder="例如: 1000">
          <template #append>ms</template>
        </el-input>
      </el-form-item>
      <el-form-item label="内存限制">
        <el-input v-model="form.memoryLimit" placeholder="例如: 256">
          <template #append>MB</template>
        </el-input>
      </el-form-item>
      <el-form-item label="标签">
        <el-input v-model="tagsStr" placeholder="逗号分隔，如: dp,math" />
      </el-form-item>
      <el-form-item label="题目来源">
        <el-select v-model="form.sourceType" placeholder="请选择来源类型">
          <el-option label="个人上传" value="personal" />
          <el-option label="官方题" value="official" />
        </el-select>
      </el-form-item>
      <el-form-item label="来源名称">
        <el-input
          v-model="form.sourceName"
          placeholder="如: LeetCode / 官方题库"
        />
      </el-form-item>
      <el-form-item label="来源链接">
        <el-input
          v-model="form.sourceLink"
          placeholder="如: https://leetcode.cn/problems/..."
        />
      </el-form-item>
      <el-form-item label="题解">
        <el-input
          type="textarea"
          :rows="6"
          v-model="form.solution"
          placeholder="个人上传题目建议填写题解，官方题可留空"
        />
      </el-form-item>

      <div class="editor-tabs">
        <el-tabs type="card" v-model="activeEditor">
          <el-tab-pane label="题目描述" name="desc">
            <el-input
              type="textarea"
              :rows="10"
              v-model="form.description"
              placeholder="支持 Markdown"
            />
          </el-tab-pane>
          <el-tab-pane label="输入格式" name="input">
            <el-input
              type="textarea"
              :rows="5"
              v-model="form.inputStyle"
              placeholder="支持 Markdown"
            />
          </el-tab-pane>
          <el-tab-pane label="输出格式" name="output">
            <el-input
              type="textarea"
              :rows="5"
              v-model="form.outputStyle"
              placeholder="支持 Markdown"
            />
          </el-tab-pane>
          <el-tab-pane label="样例输入" name="sampleIn">
            <el-input
              type="textarea"
              :rows="5"
              v-model="samplesInStr"
              placeholder="样例之间使用 '---' 分隔"
            />
            <div class="tip">样例之间使用 '---' (单独一行) 分隔</div>
          </el-tab-pane>
          <el-tab-pane label="样例输出" name="sampleOut">
            <el-input
              type="textarea"
              :rows="5"
              v-model="samplesOutStr"
              placeholder="样例之间使用 '---' 分隔"
            />
            <div class="tip">样例之间使用 '---' (单独一行) 分隔</div>
          </el-tab-pane>
          <el-tab-pane label="提示" name="range">
            <el-input
              type="textarea"
              :rows="5"
              v-model="form.hint"
              placeholder="支持 Markdown"
            />
          </el-tab-pane>
          <el-tab-pane label="测试用例" name="testCases">
            <div class="test-case-list">
              <div
                v-for="(caseItem, index) in testCases"
                :key="index"
                class="test-case-item"
              >
                <div class="test-case-header">
                  <span>测试用例 {{ index + 1 }}</span>
                  <el-button
                    type="text"
                    size="small"
                    @click="removeTestCase(index)"
                    v-if="testCases.length > 1"
                    >删除</el-button
                  >
                </div>
                <el-form-item label="输入" :label-width="'60px'">
                  <el-input
                    type="textarea"
                    :rows="3"
                    v-model="caseItem.input"
                    placeholder="本测试用例的输入"
                  />
                </el-form-item>
                <el-form-item label="输出" :label-width="'60px'">
                  <el-input
                    type="textarea"
                    :rows="3"
                    v-model="caseItem.output"
                    placeholder="本测试用例的标准输出"
                  />
                </el-form-item>
                <el-divider />
              </div>
              <el-button
                type="primary"
                plain
                icon="el-icon-plus"
                @click="addTestCase"
                >添加测试用例</el-button
              >
              <div class="tip">
                每组测试用例为一条输入/输出对，提交时会自动处理成判题用例。
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="测试用例生成" name="checker">
            <!-- 变量卡片 -->
            <div class="gen-section">
              <div class="gen-section-title">
                <span class="gen-dot"></span> 输入变量
                <el-button link type="primary" size="small" @click="autoExtractVariables" style="margin-left:auto">
                  重新解析
                </el-button>
              </div>
              <div class="gen-var-cards">
                <div v-for="(v, idx) in genVars" :key="idx" class="gen-var-card">
                  <div class="gen-var-top">
                    <span class="gen-var-name">{{ v.name }}</span>
                    <el-button link type="danger" size="small" @click="genVars.splice(idx,1)">✕</el-button>
                  </div>
                  <div class="gen-var-body">
                    <el-radio-group v-model="v.varType" size="small">
                      <el-radio-button value="int">整数</el-radio-button>
                      <el-radio-button value="int[]">数组</el-radio-button>
                      <el-radio-button value="string">字符串</el-radio-button>
                    </el-radio-group>
                    <div class="gen-var-range">
                      <template v-if="v.varType === 'int[]'">
                        <span class="gen-label">长度</span>
                        <el-input v-model="v.sizeVar" size="small" style="width:50px" />
                        <span class="gen-label">元素</span>
                        <el-input v-model="v.min" size="small" style="width:70px" />
                        <span>~</span>
                        <el-input v-model="v.max" size="small" style="width:70px" />
                      </template>
                      <template v-else-if="v.varType === 'string'">
                        <span class="gen-label">长度</span>
                        <el-input-number v-model="v.min" size="small" :min="1" controls-position="right" style="width:100px" />
                        <span>~</span>
                        <el-input-number v-model="v.max" size="small" :min="1" controls-position="right" style="width:100px" />
                        <el-select v-model="v.charset" size="small" style="width:85px;margin-left:8px">
                          <el-option label="字母+数字" value="mixed" />
                          <el-option label="小写字母" value="lower" />
                          <el-option label="大写字母" value="upper" />
                          <el-option label="纯数字" value="digit" />
                        </el-select>
                      </template>
                      <template v-else>
                        <el-input v-model="v.min" size="small" style="width:70px" />
                        <span>~</span>
                        <el-input v-model="v.max" size="small" style="width:70px" />
                      </template>
                    </div>
                  </div>
                </div>
                <div class="gen-var-card gen-var-add" @click="addGenVar()">
                  <span>+ 添加变量</span>
                </div>
              </div>
            </div>

            <!-- 标程 + 生成 -->
            <div class="gen-section">
              <div class="gen-section-title"><span class="gen-dot"></span> 标程</div>
              <div class="gen-std-row">
                <el-select v-model="form.standardLang" placeholder="语言" style="width:100px">
                  <el-option label="C++" value="cpp" />
                  <el-option label="Python" value="python" />
                  <el-option label="Java" value="java" />
                </el-select>
                <el-input type="textarea" :rows="6" v-model="form.standardCode"
                  placeholder="正确解法代码..." style="font-family:monospace;flex:1" />
              </div>
            </div>

            <div class="gen-section">
              <div class="gen-section-title"><span class="gen-dot"></span> 生成</div>
              <div class="gen-actions">
                <span style="color:var(--text-secondary);font-size:14px">生成</span>
                <el-input-number v-model="genRounds" :min="1" :max="20" size="small" style="width:80px" />
                <span style="color:var(--text-secondary);font-size:14px">组测试用例</span>
                <el-button type="primary" @click="doGenerateTestCases" :loading="genLoading">
                  开始生成
                </el-button>
                <el-button v-if="genResults.length > 0" @click="applyGeneratedCases" plain type="warning">
                  应用到题目
                </el-button>
              </div>
            </div>

            <!-- 结果 -->
            <div v-if="genResults.length > 0" class="gen-section">
              <div class="gen-section-title">
                <span class="gen-dot" style="background:#67c23a"></span>
                生成结果（{{ genResults.length }} 组）
              </div>
              <div class="gen-result-list">
                <div v-for="(tc, idx) in genResults" :key="idx" class="gen-result-item">
                  <div class="gen-result-num">#{{ idx + 1 }}</div>
                  <div class="gen-result-io">
                    <div class="gen-io-block">
                      <span class="gen-io-label">输入</span>
                      <el-input type="textarea" :rows="2" v-model="tc.input" />
                    </div>
                    <div class="gen-io-block">
                      <span class="gen-io-label">输出</span>
                      <el-input type="textarea" :rows="2" v-model="tc.output" />
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
      <div class="form-actions">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="loading"
          >确定</el-button
        >
      </div>
    </el-form>
  </div>
</template>

<script lang="ts" setup>
import { ref, reactive, computed, watch } from "vue";
import { ElMessage } from "element-plus";
import {
  reqCreateProblem,
  reqUpdateProblem,
  reqProblemDetail,
} from "@/api/problem";

const emit = defineEmits(["refresh"]);

const visible = ref(false);
const isEdit = ref(false);
const loading = ref(false);
const activeEditor = ref("desc");
const tagsStr = ref("");

const form = reactive({
  id: undefined,
  name: "",
  difficulty: "简单",
  timeLimit: "",
  memoryLimit: "",
  tags: [] as string[],
  sourceType: "personal",
  sourceName: "",
  sourceLink: "",
  solution: "",
  description: "",
  inputStyle: "",
  outputStyle: "",
  inputSample: [] as string[],
  outputSample: [] as string[],
  hint: "",
  testData: "",
  testAns: "",
  checkerConfig: "",
  standardCode: "",
  standardLang: "",
});

const checkerConfigStr = ref("");

const samplesInStr = ref("");
const samplesOutStr = ref("");
const testCases = ref([{ input: "", output: "" }]);

const open = async (row?: any) => {
  visible.value = true;
  if (row) {
    isEdit.value = true;
    try {
      const res = await reqProblemDetail(String(row.id));
      const data = res.data.data;
      Object.assign(form, data);

      tagsStr.value = (data.tags || []).join(",");
      samplesInStr.value = (data.inputSample || []).join("\n---\n");
      samplesOutStr.value = (data.outputSample || []).join("\n---\n");
      testCases.value = buildTestCases(data.testData || "", data.testAns || "");
      if (testCases.value.length === 0) {
        testCases.value = [{ input: "", output: "" }];
      }
      checkerConfigStr.value = data.checkerConfig || "";
      form.standardCode = data.standardCode || "";
      form.standardLang = data.standardLang || "";
      if (form.inputStyle) autoExtractVariables();
    } catch (e) {
      ElMessage.error("加载详情失败");
    }
  } else {
    isEdit.value = false;
    resetForm();
  }
};

const resetForm = () => {
  form.id = undefined;
  form.name = "";
  form.difficulty = "简单";
  form.timeLimit = "1000";
  form.memoryLimit = "256";
  tagsStr.value = "";
  samplesInStr.value = "";
  samplesOutStr.value = "";
  form.description = "";
  form.inputStyle = "";
  form.outputStyle = "";
  form.inputSample = [];
  form.outputSample = [];
  form.hint = "";
  form.sourceType = "personal";
  form.sourceName = "";
  form.sourceLink = "";
  form.solution = "";
  form.testData = "";
  form.testAns = "";
  form.checkerConfig = "";
  form.standardCode = "";
  form.standardLang = "";
  checkerConfigStr.value = "";
  testCases.value = [{ input: "", output: "" }];
};

const addTestCase = () => {
  testCases.value.push({ input: "", output: "" });
};

const removeTestCase = (index: number) => {
  if (testCases.value.length <= 1) {
    return;
  }
  testCases.value.splice(index, 1);
};

const buildTestCases = (rawInput: string, rawOutput: string) => {
  const inputCases = rawInput
    ? rawInput.split("\n---\n").map((s) => s.trim())
    : [];
  const outputCases = rawOutput
    ? rawOutput.split("\n---\n").map((s) => s.trim())
    : [];
  const length = Math.max(inputCases.length, outputCases.length);
  return Array.from({ length }, (_, index) => ({
    input: inputCases[index] || "",
    output: outputCases[index] || "",
  }));
};

// ── 测试用例生成 ──────────────────────────────────────────────
import { reqGenerateTestCases } from "@/api/problem";

const genRounds = ref(10);
const genLoading = ref(false);
const genResults = ref<Array<{ input: string; output: string }>>([]);

interface GenVar {
  name: string; varType: string; min: number | string; max: number | string;
  sizeVar: string; charset: string;
}

const genVars = ref<GenVar[]>([]);

const addGenVar = () => {
  genVars.value.push({
    name: "var" + genVars.value.length,
    varType: "int", min: 1, max: 1000,
    sizeVar: "n", charset: "mixed",
  });
};

const autoExtractVariables = () => {
  const raw = (checkerConfigStr.value || form.checkerConfig || "").trim();
  if (!raw) {
    // fallback: 从 inputStyle 解析
    const vars: GenVar[] = [];
    const seen = new Set<string>();
    const inputLines = (form.inputStyle || "").split("\n").filter(l => l.trim());
    for (const line of inputLines) {
      const name = line.split(/[:：]/)[0]?.trim();
      if (!name || name.length > 20 || seen.has(name)) continue;
      const lower = line.toLowerCase();
      seen.add(name);
      if (lower.includes("数组") || lower.includes("[]") || lower.includes("list")) {
        vars.push({ name, varType: "int[]", min: -1000, max: 1000, sizeVar: "n", charset: "mixed" });
      } else if (lower.includes("字符串") || lower.includes("string")) {
        vars.push({ name, varType: "string", min: 1, max: 1000, sizeVar: "", charset: "mixed" });
      } else {
        vars.push({ name, varType: "int", min: -1000, max: 1000, sizeVar: "", charset: "mixed" });
      }
    }
    if (vars.length > 0) genVars.value = vars;
    return;
  }

  // 解析 checkerConfig 文本
  const vars: GenVar[] = [];
  const lines = raw.split("\n");
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || trimmed.startsWith("rounds")) continue;
    const parts = trimmed.split(/\s+/);
    const name = parts[0];
    if (parts.length >= 4 && /^(int|long|double|string|char|bool)\[/.test(parts[1])) {
      // 数组: name type[sizeVar] [min max [charset]]
      const m = parts[1].match(/^(\w+)\[(\w+)\]$/);
      if (!m) continue;
      const elemType = m[1];
      const sizeVar = m[2];
      if (elemType === "string") {
        vars.push({ name, varType: "string[]", min: parts[2], max: parts[3], sizeVar, charset: parts[4] || "mixed" });
      } else {
        vars.push({ name, varType: "int[]", min: parts[2], max: parts[3], sizeVar, charset: "mixed" });
      }
    } else if (parts.length === 3 && parts[1] === "string") {
      vars.push({ name, varType: "string", min: parts[2], max: parts[3], sizeVar: "", charset: parts[4] || "mixed" });
    } else if (parts.length === 2 && (parts[1] === "bool" || parts[1] === "char")) {
      vars.push({ name, varType: parts[1], min: 0, max: 0, sizeVar: "", charset: parts[2] || "mixed" });
    } else if (parts.length >= 3) {
      // int / size 变量: name min max（min/max 可为数字或变量引用）
      vars.push({ name, varType: "int", min: parts[1], max: parts[2], sizeVar: "", charset: "mixed" });
    }
  }
  if (vars.length > 0) genVars.value = vars;
};

const buildCheckerConfigFromVars = (): string => {
  const lines: string[] = [];
  for (const v of genVars.value) {
    if (v.varType === "string") {
      lines.push(`${v.name} string ${v.min} ${v.max} ${v.charset}`);
    } else if (v.varType === "int[]") {
      lines.push(`${v.name} int[${v.sizeVar}] ${v.min} ${v.max}`);
    } else if (v.varType === "bool" || v.varType === "char") {
      lines.push(`${v.name} ${v.varType}${v.charset !== "mixed" ? " " + v.charset : ""}`);
    } else {
      // int / size 变量，min/max 可为数字或变量引用
      lines.push(`${v.name} ${v.min} ${v.max}`);
    }
  }
  lines.push(`rounds ${genRounds.value}`);
  return lines.join("\n");
};

const doGenerateTestCases = async () => {
  if (genVars.value.length === 0 || !form.standardCode.trim()) {
    ElMessage.warning("请先填写变量范围和标程代码");
    return;
  }
  genLoading.value = true;
  try {
    const res = await reqGenerateTestCases({
      checkerConfig: buildCheckerConfigFromVars(),
      standardCode: form.standardCode,
      standardLang: form.standardLang || "cpp",
      rounds: genRounds.value,
    });
    if (res.data.code === 200) {
      genResults.value = res.data.data || [];
      ElMessage.success(`成功生成 ${genResults.value.length} 组测试用例`);
    } else {
      ElMessage.error(res.data.msg || "生成失败");
    }
  } catch (e: any) {
    ElMessage.error("生成失败: " + (e?.message || "未知错误"));
  }
  genLoading.value = false;
};

const applyGeneratedCases = () => {
  testCases.value = [...genResults.value];
  ElMessage.success("已应用到题目测试用例");
};


// ---- 约束解析：从提示/描述中提取 1 <= varName <= 999 等范围 ----
const parseConstraints = (text: string): Map<string, { min: number; max: number }> => {
  const map = new Map<string, { min: number; max: number }>();
  if (!text) return map;
  // 匹配: 数字 <=/≤/&lt;= 变量名(.length/.长度)? <=/≤/&lt;= 数字
  const re = /(\d+)\s*(?:≤|<=|&lt;=)\s*(\w+?)(?:\.(?:length|长度))?\s*(?:≤|<=|&lt;=)\s*(\d+)/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const vname = m[2].toLowerCase();
    if (vname && vname.length <= 10 && !map.has(vname)) {
      map.set(vname, { min: parseInt(m[1]), max: parseInt(m[3]) });
    }
  }
  return map;
};

// ---- 检测变量是否为字符串类型 ----
const isStringVar = (name: string, text: string): boolean => {
  if (!text) return false;
  const re = new RegExp(
    `${name}\\s*(?:由|由英文字母|consists of|由.*组成|是.*字符|is.*string|charset)`,
    'i'
  );
  return re.test(text);
};

// ---- 从 inputStyle 自动生成变量规则（使用提示中的精确约束） ----
const autoParseInputStyle = () => {
  const text = form.inputStyle;
  if (!text || !text.trim()) {
    ElMessage.warning("请先填写输入格式");
    return;
  }

  // 从提示 + 描述中提取精确约束
  const hints = (form.hint || '') + '\n' + (form.description || '');
  const constraints = parseConstraints(hints);

  // 根据约束获取范围，未找到时用合理默认值
  const intRange = (name: string) => {
    const c = constraints.get(name.toLowerCase());
    return c ? `${c.min} ${c.max}` : '1 200000';
  };
  const arrElemRange = (name: string) => {
    const c = constraints.get(name.toLowerCase());
    return c ? `${c.min} ${c.max}` : '-1000000000 1000000000';
  };

  const lines = text.split("\n").map((l) => l.trim()).filter((l) => l);
  const result: string[] = [];
  let arrCounter = 0;

  // 结构化格式：name: 类型描述
  const structLines = lines.filter((l) => /^\w+\s*:\s*/.test(l));
  if (structLines.length > 0) {
    for (const line of structLines) {
      const match = line.match(/^(\w+)\s*:\s*(.+)$/);
      if (!match) continue;
      const name = match[1];
      const desc = match[2];

      if (isStringVar(name, hints)) {
        const sc = constraints.get(name.toLowerCase());
        const lo = sc ? sc.min : 1;
        const hi = sc ? sc.max : 1000;
        result.push(`${name} string ${lo} ${hi} lower`);
      } else if (/整数数组|链表.*数组|ListNode/i.test(desc)) {
        const sizeVar = arrCounter === 0 ? "n" : "m" + arrCounter;
        result.push(sizeVar + " " + intRange(sizeVar));
        const range = /链表/i.test(desc) ? "0 9" : arrElemRange(name);
        result.push(name + " int[" + sizeVar + "] " + range);
        arrCounter++;
      } else if (/整数\b|integer/i.test(desc)) {
        result.push(name + " " + intRange(name));
      } else if (/浮点|double/i.test(desc)) {
        result.push(name + " " + intRange(name));
      } else if (/字符串|string/i.test(desc)) {
        const sc = constraints.get(name.toLowerCase());
        const lo = sc ? sc.min : 1;
        const hi = sc ? sc.max : 1000;
        result.push(`${name} string ${lo} ${hi} lower`);
      } else if (/布尔|boolean/i.test(desc)) {
        result.push(name + " 0 1");
      } else if (/树|TreeNode/i.test(desc)) {
        result.push("# " + name + " 树类型，暂不支持自动生成，请手动填写");
      }
    }
  } else {
    // 自然语言解析（"第一行一个整数 n" 等中文格式）
    for (const line of lines) {
      const countArrMatch = line.match(/(?:第\S+行\s*)?(\w+)\s*个?\s*(?:整数|数字|个数)/);
      if (countArrMatch && countArrMatch[1].length <= 3) {
        const nName = countArrMatch[1].match(/^\d+$/) ? "n" : countArrMatch[1];
        result.push(nName + " " + intRange(nName));
        result.push("arr" + (arrCounter > 0 ? arrCounter : "") + " int[" + nName + "] " + arrElemRange(nName));
        arrCounter++;
        continue;
      }
      const strMatch = line.match(/(?:一个|一行)?\s*(?:字符串|string)\s*(\w+)/i);
      if (strMatch) {
        const name = strMatch[1];
        const sc = constraints.get(name.toLowerCase());
        const lo = sc ? sc.min : 1;
        const hi = sc ? sc.max : 1000;
        result.push(`${name} string ${lo} ${hi} lower`);
        continue;
      }
      const singleVar = line.match(/(?:一个|两个|三个)?\s*(?:整数|数字)\s*(\w+)/g);
      if (singleVar) {
        for (const m of singleVar) {
          const name = m.replace(/(?:一个|两个|三个)?\s*(?:整数|数字)\s*/, "").trim();
          if (name && name.length <= 10 && !name.match(/^\d+$/)) {
            result.push(name + " " + intRange(name));
          }
        }
      }
    }
  }

  if (result.length === 0) {
    ElMessage.warning("未能自动解析，请手动填写变量规则");
    return;
  }

  result.push("rounds 20");
  checkerConfigStr.value = result.join("\n");
  const matched = Array.from(constraints.keys()).join(', ') || '无';
  ElMessage.success(`已生成 ${result.length - 1} 个变量（约束匹配: ${matched}）`);
};

const formatJson = (str: string | null | undefined): string | null => {
  if (!str) return null;
  if (str.trim().startsWith("{")) {
    try {
      return JSON.stringify(JSON.parse(str), null, 2);
    } catch {
      return str;
    }
  }
  return str;
};

const submit = async () => {
  loading.value = true;
  try {
    form.tags = tagsStr.value
      ? tagsStr.value
          .split(",")
          .map((t) => t.trim())
          .filter((t) => t)
      : [];

    form.inputSample = samplesInStr.value
      ? samplesInStr.value.split("\n---\n").map((s) => s.trim())
      : [];
    form.outputSample = samplesOutStr.value
      ? samplesOutStr.value.split("\n---\n").map((s) => s.trim())
      : [];

    const tl = Number(form.timeLimit);
    const ml = Number(form.memoryLimit);
    if (!tl || tl <= 0) {
      ElMessage.warning("请设置时间限制（ms），LeetCode 导入的题目需手动填写");
      return;
    }
    if (!ml || ml <= 0) {
      ElMessage.warning("请设置内存限制（MB），LeetCode 导入的题目需手动填写");
      return;
    }

    if (form.inputSample.length !== form.outputSample.length) {
      ElMessage.warning("输入样例和输出样例数量不匹配");
      return;
    }

    const isCheckerMode = form.standardCode?.trim() && form.standardLang?.trim() && checkerConfigStr.value?.trim();

    const nonEmptyCases = testCases.value.filter(
      (item) => item.input.trim() !== "" || item.output.trim() !== "",
    );
    if (nonEmptyCases.length === 0 && !isCheckerMode) {
      ElMessage.warning("请至少添加一组测试用例，或配置 Checker 模式（标程 + 变量规则）");
      return;
    }

    for (let i = 0; i < nonEmptyCases.length; i++) {
      const item = nonEmptyCases[i];
      if (!item.input.trim() || !item.output.trim()) {
        ElMessage.warning(`第 ${i + 1} 组测试用例的输入和输出都不能为空`);
        return;
      }
    }

    form.testData = nonEmptyCases
      .map((item) => item.input.trim())
      .join("\n---\n");
    form.testAns = nonEmptyCases
      .map((item) => item.output.trim())
      .join("\n---\n");

    // 将 genVars 的编辑同步回 checkerConfig
    if (genVars.value.length > 0) {
      checkerConfigStr.value = buildCheckerConfigFromVars();
    }
    const rawConfig = checkerConfigStr.value?.trim() || "";
    if (rawConfig && rawConfig.startsWith("{")) {
      try {
        form.checkerConfig = JSON.stringify(JSON.parse(rawConfig));
      } catch {
        ElMessage.warning("JSON 格式不正确");
        return;
      }
    } else {
      form.checkerConfig = rawConfig;
    }

    const payload = {
      ...form,
      timeLimit: Number(form.timeLimit),
      memoryLimit: Number(form.memoryLimit),
    };

    let res;
    if (isEdit.value) {
      res = await reqUpdateProblem(payload as any);
    } else {
      res = await reqCreateProblem(payload as any);
    }

    if (res.data.code === 200) {
      ElMessage.success(isEdit.value ? "修改成功" : "新增成功");
      visible.value = false;
      emit("refresh");
    } else {
      ElMessage.error(res.data.message || "操作失败");
    }
  } catch (e) {
    ElMessage.error("请求出错");
  } finally {
    loading.value = false;
  }
};

defineExpose({ open });
</script>

<style scoped>
.tip {
  font-size: 12px;
  color: #999;
  margin-top: 5px;
}
.problem-form {
  background: #fff;
  padding: 24px;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}
.form-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.form-title {
  font-size: 18px;
  font-weight: 700;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 24px;
}
pre {
  margin: 0;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
}
.checker-config-area {
  width: 100%;
}
.config-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.config-header span {
  font-size: 13px;
  color: #606266;
}

/* ── 测试用例生成面板 ──────────────────────────────────────── */
.gen-section {
  margin-bottom: 20px;
}
.gen-section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-primary);
  margin-bottom: 10px;
}
.gen-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  background: var(--primary-start);
  display: inline-block;
}
.gen-var-cards {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.gen-var-card {
  background: var(--bg-elevated);
  border: 1px solid var(--border-color);
  border-radius: 8px;
  padding: 12px 14px;
  min-width: 220px;
  flex: 1;
  max-width: 320px;
  transition: border-color .2s;
  &:hover { border-color: var(--primary-start); }
}
.gen-var-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.gen-var-name {
  font-weight: 700;
  font-size: 15px;
  font-family: monospace;
  color: var(--primary-start);
}
.gen-var-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gen-var-range {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  font-size: 13px;
}
.gen-label {
  font-size: 12px;
  color: var(--text-secondary);
  min-width: 28px;
}
.gen-var-add {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 1px dashed var(--border-color);
  cursor: pointer;
  color: var(--text-secondary);
  min-width: 120px;
  max-width: 160px;
  &:hover { color: var(--primary-start); border-color: var(--primary-start); }
}
.gen-std-row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  :deep(.el-textarea__inner) { min-height: 140px; }
}
.gen-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.gen-result-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 500px;
  overflow-y: auto;
}
.gen-result-item {
  background: var(--bg-elevated);
  border: 1px solid var(--border-color);
  border-radius: 6px;
  padding: 8px 12px;
  display: flex;
  gap: 10px;
  align-items: flex-start;
}
.gen-result-num {
  font-weight: 700;
  font-size: 13px;
  color: var(--primary-start);
  min-width: 28px;
  padding-top: 4px;
}
.gen-result-io {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.gen-io-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.gen-io-label {
  font-size: 11px;
  color: var(--text-secondary);
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
</style>
