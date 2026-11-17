#!/usr/bin/env node
/**
 * 每日建议模板校验脚本。
 *
 * 用法：node tools/suggestion_template_check.js [模板文件路径]
 * 默认校验 app/src/main/assets/suggestion_templates.json。
 *
 * 校验规则见 docs/每日建议引擎设计方案.md 第 14.6 节。
 * 任一错误都会以非 0 退出码结束，便于接入打包流程。
 */
const fs = require('fs');
const path = require('path');

const DEFAULT_FILE = path.join(
    __dirname, '..', 'app', 'src', 'main', 'assets', 'suggestion_templates.json');

const SLOTS = ['opening', 'focus', 'tip', 'nudge', 'insight', 'yiji'];
const PHASES = ['ALL', 'MENSTRUAL_PERIOD', 'FOLLICULAR_PHASE', 'OVULATION_PERIOD', 'LUTE_PHASE'];
const TONES = ['gentle', 'brief', 'cheerful'];
const TOPICS = ['warmth', 'sleep', 'diet', 'exercise', 'mood', 'hygiene', 'medical', 'rest', 'record'];
const PLACEHOLDERS = [
    '{{phaseName}}', '{{dayInCycle}}', '{{daysToNextPeriod}}',
    '{{painText}}', '{{flowText}}', '{{weatherText}}', '{{temp}}', '{{streak}}',
    '{{reminderTime}}', '{{reminderTitle}}',
    // A6 里程碑、B4 节气节日、B6 周期趋势
    '{{usageDays}}', '{{cycleNo}}', '{{festivalName}}', '{{cycleDiff}}'
];
const FORBIDDEN_WORDS = ['治愈', '根治', '一定', '必须就医', '包治', '疗程', '保证'];
const ACTION_WORDS = [
    '热敷', '补水', '喝', '吃', '早睡', '睡', '散步', '走', '泡脚', '拉伸',
    '换', '通风', '休息', '深呼吸', '躺', '记录', '补充', '洗', '保暖', '备好', '晒太阳', '防护',
    '服用', '处理'
];
/** 占位符与标签的依赖关系：用了哪个变量，就必须声明对应标签，否则会在无数据日期被选中后无法渲染 */
const PLACEHOLDER_TAG_REQUIREMENTS = {
    '{{painText}}': ['pain_mild', 'pain_moderate', 'pain_severe_plus'],
    '{{flowText}}': ['flow_heavy'],
    '{{temp}}': ['cold_weather', 'hot_weather'],
    '{{weatherText}}': ['cold_weather', 'hot_weather', 'rainy', 'sunny'],
    '{{reminderTime}}': ['has_reminder'],
    '{{reminderTitle}}': ['has_reminder'],
    '{{usageDays}}': ['milestone_usage'],
    '{{cycleNo}}': ['milestone_cycle'],
    '{{festivalName}}': ['solar_term', 'festival'],
    '{{cycleDiff}}': ['cycle_shorter', 'cycle_longer']
};

const errors = [];
const warnings = [];

function fail(message) {
    errors.push(message);
}

function warn(message) {
    warnings.push(message);
}

function lengthOf(text) {
    return Array.from(text).length;
}

/** 中文按字切分，英文/数字按词切分，用于 4-gram 相似度计算 */
function toGrams(text) {
    const cleaned = text.replace(/[{{}}（）()，。、；：！？｜·]/g, ' ').replace(/\s+/g, ' ').trim();
    const chars = Array.from(cleaned.replace(/\s/g, ''));
    const grams = new Set();
    for (let i = 0; i + 4 <= chars.length; i++) {
        grams.add(chars.slice(i, i + 4).join(''));
    }
    return grams;
}

function similarity(a, b) {
    const ga = toGrams(a);
    const gb = toGrams(b);
    if (ga.size === 0 || gb.size === 0) return 0;
    let intersection = 0;
    for (const gram of ga) {
        if (gb.has(gram)) intersection++;
    }
    return intersection / (ga.size + gb.size - intersection);
}

function main() {
    const file = process.argv[2] ? path.resolve(process.argv[2]) : DEFAULT_FILE;
    if (!fs.existsSync(file)) {
        console.error(`模板文件不存在: ${file}`);
        process.exit(1);
    }

    let data;
    try {
        data = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
        console.error(`JSON 解析失败: ${e.message}`);
        process.exit(1);
    }

    if (typeof data.schemaVersion !== 'number') fail('缺少 schemaVersion');
    if (typeof data.contentVersion !== 'number') fail('缺少 contentVersion');
    if (!Array.isArray(data.templates) || data.templates.length === 0) {
        console.error('templates 为空');
        process.exit(1);
    }

    const ids = new Set();
    const texts = [];

    data.templates.forEach((item, index) => {
        const label = item && item.id ? item.id : `#${index}`;

        if (!item.id) {
            fail(`${label}: 缺少 id`);
        } else if (ids.has(item.id)) {
            fail(`${label}: id 重复`);
        } else {
            ids.add(item.id);
        }

        if (!SLOTS.includes(item.slot)) fail(`${label}: slot 非法 -> ${item.slot}`);
        if (!TONES.includes(item.tone)) fail(`${label}: tone 非法 -> ${item.tone}`);
        if (!TOPICS.includes(item.topic)) fail(`${label}: topic 非法 -> ${item.topic}`);

        const phases = item.phase || item.phases || [];
        if (!Array.isArray(phases) || phases.length === 0) {
            fail(`${label}: phase 不能为空`);
        } else {
            phases.forEach(p => {
                if (!PHASES.includes(p)) fail(`${label}: phase 非法 -> ${p}`);
            });
        }

        const text = item.template || '';
        const len = lengthOf(text);
        if (len < 10 || len > 60) fail(`${label}: 模板长度 ${len}，应在 10-60 字之间`);

        const found = text.match(/\{\{[^}]+\}\}/g) || [];
        found.forEach(ph => {
            if (!PLACEHOLDERS.includes(ph)) fail(`${label}: 未知占位符 ${ph}`);
        });

        FORBIDDEN_WORDS.forEach(word => {
            if (text.includes(word)) fail(`${label}: 命中禁用词 "${word}"`);
        });

        // 依据类文案是解释而非建议，不强制包含动作词
        if (item.slot !== 'insight' && !ACTION_WORDS.some(w => text.includes(w))) {
            fail(`${label}: 缺少具体动作词`);
        }

        const tags = item.tags || [];
        Object.keys(PLACEHOLDER_TAG_REQUIREMENTS).forEach(ph => {
            if (text.includes(ph)) {
                const required = PLACEHOLDER_TAG_REQUIREMENTS[ph];
                if (!tags.some(t => required.includes(t))) {
                    fail(`${label}: 使用 ${ph} 但未声明依赖标签 ${required.join('/')}`);
                }
            }
        });

        texts.push({ id: label, text });
    });

    // 重复度检查
    for (let i = 0; i < texts.length; i++) {
        for (let j = i + 1; j < texts.length; j++) {
            const sim = similarity(texts[i].text, texts[j].text);
            if (sim > 0.6) {
                fail(`${texts[i].id} 与 ${texts[j].id} 相似度过高: ${sim.toFixed(2)}`);
            }
        }
    }

    // 基础覆盖度：种子阶段的最低要求，全量扩产时这里会提高
    const bySlot = {};
    const focusByPhase = {};
    data.templates.forEach(item => {
        bySlot[item.slot] = (bySlot[item.slot] || 0) + 1;
        if (item.slot === 'focus') {
            (item.phase || item.phases || []).forEach(p => {
                focusByPhase[p] = (focusByPhase[p] || 0) + 1;
            });
        }
    });

    ['opening', 'focus', 'tip', 'nudge', 'yiji', 'insight'].forEach(slot => {
        if (!bySlot[slot]) fail(`插槽 ${slot} 没有任何模板`);
    });
    ['MENSTRUAL_PERIOD', 'FOLLICULAR_PHASE', 'OVULATION_PERIOD', 'LUTE_PHASE'].forEach(phase => {
        const count = focusByPhase[phase] || 0;
        if (count < 6) {
            warn(`阶段 ${phase} 的 focus 模板只有 ${count} 条，建议至少 6 条`);
        }
    });

    // 覆盖矩阵：每个“阶段 × 天段”至少 2 条，缺格直接判失败（见设计文档 14.4）
    const FOCUS_MATRIX = {
        MENSTRUAL_PERIOD: ['day_1', 'day_2_3', 'day_4_plus'],
        FOLLICULAR_PHASE: ['follicular_early', 'follicular_near_ovulation'],
        OVULATION_PERIOD: ['ovulation_day'],
        LUTE_PHASE: ['luteal_early', 'luteal_mid', 'pre_period_3d']
    };
    Object.keys(FOCUS_MATRIX).forEach(phase => {
        FOCUS_MATRIX[phase].forEach(segment => {
            const count = data.templates.filter(item =>
                item.slot === 'focus'
                && (item.phase || item.phases || []).includes(phase)
                && (item.tags || []).includes(segment)).length;
            if (count < 2) {
                fail(`覆盖矩阵缺格: ${phase} / ${segment} 只有 ${count} 条`);
            }
        });
    });

    // 通用主题：睡眠、饮水、久坐、情绪、就医各至少 2 条
    ['sleep', 'diet', 'exercise', 'mood', 'medical'].forEach(topic => {
        const count = data.templates.filter(item =>
            item.slot === 'focus'
            && (item.phase || item.phases || []).includes('ALL')
            && item.topic === topic).length;
        if (count < 2) {
            fail(`通用主题 ${topic} 只有 ${count} 条，至少需要 2 条`);
        }
    });

    console.log(`共校验 ${data.templates.length} 条模板`);
    console.log(`插槽分布: ${JSON.stringify(bySlot)}`);
    if (warnings.length) {
        console.log('\n警告:');
        warnings.forEach(w => console.log('  - ' + w));
    }
    if (errors.length) {
        console.log('\n错误:');
        errors.forEach(e => console.log('  - ' + e));
        console.log(`\n校验失败：${errors.length} 个错误`);
        process.exit(1);
    }
    console.log('\n校验通过');
}

main();
