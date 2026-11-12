// src/cli.ts
import { closeSync, lstatSync as lstatSync3, mkdirSync, openSync, readFileSync as readFileSync5, renameSync, rmSync, statSync, writeSync } from "node:fs";
import { basename, dirname, join as join6, resolve } from "node:path";

// src/config.ts
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

// src/glob.ts
var REGEX_SPECIAL = /[\\^$.|+()[\]{}*?]/g;
function escapeRegex(text) {
  return text.replace(REGEX_SPECIAL, "\\$&");
}
function closeOfSet(pattern, start) {
  return pattern.indexOf("]", start + 2);
}
function closeOfBrace(pattern, start) {
  let depth = 0;
  for (let i = start; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === "[") {
      const end = closeOfSet(pattern, i);
      if (end !== -1) i = end;
    } else if (c === "{") {
      depth++;
    } else if (c === "}") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}
function splitAlternatives(body) {
  const parts = [];
  let depth = 0;
  let from = 0;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === "[") {
      const end = closeOfSet(body, i);
      if (end !== -1) i = end;
    } else if (c === "{") {
      depth++;
    } else if (c === "}") {
      depth--;
    } else if (c === "," && depth === 0) {
      parts.push(body.slice(from, i));
      from = i + 1;
    }
  }
  parts.push(body.slice(from));
  return parts;
}
function setToRegex(content) {
  if (content.length === 1) return escapeRegex(content);
  let negate = false;
  let body = content;
  if (body[0] === "!" || body[0] === "^") {
    negate = true;
    body = body.slice(1);
  }
  const inner = body.replace(/[\\\]\[^]/g, "\\$&");
  return negate ? `[^/${inner}]` : `[${inner}]`;
}
var SEGMENT = "(?!\\.)[^/]*";
function toRegexSource(pattern, startsSegment = true) {
  let out = "";
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    const atSegmentStart = i === 0 ? startsSegment : pattern[i - 1] === "/";
    if (c === "*") {
      if (pattern[i + 1] === "*") {
        const next = pattern[i + 2];
        if (atSegmentStart && next === "/") {
          out += `(?:${SEGMENT}/)*`;
          i += 3;
          continue;
        }
        if (atSegmentStart && next === void 0) {
          out += `${SEGMENT}(?:/${SEGMENT})*`;
          i += 2;
          continue;
        }
        out += atSegmentStart ? SEGMENT : "[^/]*";
        i += 2;
        continue;
      }
      out += atSegmentStart ? SEGMENT : "[^/]*";
      i++;
    } else if (c === "?") {
      out += atSegmentStart ? "[^/.]" : "[^/]";
      i++;
    } else if (c === "[") {
      const end = closeOfSet(pattern, i);
      if (end === -1) {
        out += escapeRegex(c);
        i++;
      } else {
        out += setToRegex(pattern.slice(i + 1, end));
        i = end + 1;
      }
    } else if (c === "{") {
      const end = closeOfBrace(pattern, i);
      if (end === -1) {
        out += escapeRegex(c);
        i++;
      } else {
        const alternatives = splitAlternatives(pattern.slice(i + 1, end));
        const inner = alternatives.map((a) => toRegexSource(a, atSegmentStart));
        out += `(?:${inner.join("|")})`;
        i = end + 1;
      }
    } else {
      out += escapeRegex(c);
      i++;
    }
  }
  return out;
}
function compileGlob(pattern) {
  return new RegExp(`^${toRegexSource(pattern)}$`);
}
function fixedHead(pattern) {
  let out = "";
  let i = 0;
  while (i < pattern.length) {
    const c = pattern[i];
    if (c === "*" || c === "?") return out;
    if (c === "{") {
      if (closeOfBrace(pattern, i) !== -1) return out;
    } else if (c === "[") {
      const end = closeOfSet(pattern, i);
      if (end !== -1) {
        if (end !== i + 2) return out;
        out += pattern[i + 1];
        i = end + 1;
        continue;
      }
    }
    out += c;
    i++;
  }
  return out;
}
function strength(pattern) {
  return fixedHead(pattern).length;
}
var GlobSet = class {
  regexes;
  constructor(patterns) {
    this.regexes = patterns.map(compileGlob);
  }
  matches(path) {
    return this.regexes.some((r) => r.test(path));
  }
};

// src/config.ts
var LANGUAGES = {
  js: {
    detect: ["package.json"],
    codeExts: ["astro", "cjs", "js", "jsx", "mjs", "svelte", "ts", "tsx", "vue"],
    dependencyDirs: ["node_modules"],
    buildOutputDirs: ["dist", "build", ".next", ".nuxt", ".svelte-kit", "out", "coverage"],
    mainRootGlobs: [],
    testRootGlobs: [],
    testPatterns: ["**/test/**", "**/tests/**", "**/__tests__/**", "**/*.test.*", "**/*.spec.*"],
    mapperPatterns: []
  },
  java: {
    detect: ["pom.xml", "build.gradle", "build.gradle.kts"],
    codeExts: ["java", "kt"],
    dependencyDirs: [],
    buildOutputDirs: ["target", "build", "out", ".gradle"],
    mainRootGlobs: ["**/src/main/java", "**/src/main/kotlin"],
    testRootGlobs: ["**/src/test/java", "**/src/test/kotlin"],
    testPatterns: ["**/src/test/**"],
    mapperPatterns: [
      "**/src/main/resources/**/mapper/**/*.xml",
      "**/src/main/resources/**/*Mapper.xml"
    ]
  }
};
var LANGUAGE_NAMES = Object.keys(LANGUAGES).sort();
var SettingsError = class extends Error {
};
var KEYS = ["languages", "watch", "skip", "roots", "tests", "home", "page"];
var CONFIG_FILE = "codeplan.json";
function detectLanguages(repo) {
  const found = LANGUAGE_NAMES.filter(
    (name) => LANGUAGES[name].detect.some((file) => existsSync(join(repo, file)))
  );
  return found.length > 0 ? found : ["js"];
}
function stringArray(raw, key) {
  const value = raw[key];
  if (value === void 0) return void 0;
  if (!Array.isArray(value) || !value.every((v) => typeof v === "string")) {
    throw new SettingsError(`\`${key}\` must be an array of strings`);
  }
  return value;
}
function isPathForm(path) {
  return !path.includes("\\") && path.split("/").every((s) => s !== "" && s !== "." && s !== "..");
}
function isUnder(path, dir) {
  return path.startsWith(dir + "/");
}
function readConfig(repo) {
  const file = join(repo, CONFIG_FILE);
  let raw = {};
  if (existsSync(file)) {
    try {
      raw = JSON.parse(readFileSync(file, "utf8"));
    } catch (e) {
      throw new SettingsError(`${CONFIG_FILE} is not valid JSON: ${e.message}`);
    }
  }
  if (typeof raw !== "object" || raw === null || Array.isArray(raw)) {
    throw new SettingsError(`${CONFIG_FILE} must hold a JSON object`);
  }
  const obj = raw;
  for (const key of Object.keys(obj)) {
    if (!KEYS.includes(key)) {
      throw new SettingsError(`unknown key \`${key}\`; known keys: ${KEYS.join(", ")}`);
    }
  }
  let languages;
  const languageList = stringArray(obj, "languages");
  if (languageList === void 0) {
    languages = detectLanguages(repo);
  } else {
    if (languageList.length === 0) throw new SettingsError("`languages` must not be empty");
    for (const name of languageList) {
      if (!LANGUAGE_NAMES.includes(name)) {
        throw new SettingsError(
          `\`languages\` has unknown language "${name}"; known: ${LANGUAGE_NAMES.join(", ")}`
        );
      }
    }
    if (new Set(languageList).size !== languageList.length) {
      throw new SettingsError("`languages` lists a language more than once");
    }
    languages = [...languageList].sort();
  }
  if (obj.tests !== void 0 && typeof obj.tests !== "boolean") {
    throw new SettingsError("`tests` must be true or false");
  }
  const tests = obj.tests === true;
  for (const key of ["home", "page"]) {
    if (obj[key] !== void 0 && typeof obj[key] !== "string") {
      throw new SettingsError(`\`${key}\` must be a string`);
    }
  }
  const home = obj.home ?? "docs/codeplan";
  if (!isPathForm(home)) {
    throw new SettingsError(
      `\`home\` "${home}" must be a directory inside the repository, written as parts joined by "/" with no empty, "." or ".." part and no backslash`
    );
  }
  const page = obj.page ?? `${home}/codeplan.html`;
  if (!isPathForm(page)) {
    throw new SettingsError(
      `\`page\` "${page}" must be a path inside the repository, written as parts joined by "/" with no empty, "." or ".." part and no backslash`
    );
  }
  if (!isUnder(page, home)) {
    throw new SettingsError(`\`page\` "${page}" must be under home "${home}"`);
  }
  const watch = stringArray(obj, "watch") ?? null;
  for (const pattern of watch ?? []) {
    const prefix = fixedHead(pattern);
    if (prefix === home || isUnder(prefix, home)) {
      throw new SettingsError(`\`watch\` pattern "${pattern}" starts inside home "${home}"`);
    }
  }
  const roots = stringArray(obj, "roots") ?? languages.flatMap((l) => [
    ...LANGUAGES[l].mainRootGlobs,
    ...tests ? LANGUAGES[l].testRootGlobs : []
  ]);
  return {
    languages,
    watch,
    skip: stringArray(obj, "skip") ?? [],
    roots,
    tests,
    home,
    page
  };
}

// src/draft.ts
import { existsSync as existsSync2, lstatSync, readFileSync as readFileSync3 } from "node:fs";
import { join as join3 } from "node:path";

// src/lines.ts
function lineTotal(bytes) {
  let count = 0;
  for (const b of bytes) if (b === 10) count++;
  if (bytes.length > 0 && bytes[bytes.length - 1] !== 10) count++;
  return count;
}

// src/measure.ts
import { readdirSync, readFileSync as readFileSync2 } from "node:fs";
import { join as join2 } from "node:path";
var byCodeUnit = (a, b) => a < b ? -1 : a > b ? 1 : 0;
function listFiles(repo, prunedDirs) {
  const out = [];
  const visit = (dir, prefix) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = prefix + entry.name;
      if (entry.isDirectory()) {
        if (!prunedDirs.has(entry.name)) visit(join2(dir, entry.name), path + "/");
      } else if (entry.isFile()) {
        out.push(path);
      }
    }
  };
  visit(repo, "");
  return out.sort(byCodeUnit);
}
function extensionOf(path) {
  const name = path.slice(path.lastIndexOf("/") + 1);
  const dot = name.lastIndexOf(".");
  return dot <= 0 ? "" : name.slice(dot + 1);
}
function hasDotSegment(path) {
  return path.split("/").some((s) => s.startsWith("."));
}
var Rules = class {
  constructor(config) {
    this.config = config;
    const rules = config.languages.map((l) => LANGUAGES[l]);
    this.prunedDirs = /* @__PURE__ */ new Set([".git", ...rules.flatMap((r) => r.dependencyDirs)]);
    this.buildOutputDirs = new Set(rules.flatMap((r) => r.buildOutputDirs));
    this.codeExtSet = new Set(rules.flatMap((r) => r.codeExts));
    this.roots = new GlobSet(config.roots);
    this.mappers = new GlobSet(rules.flatMap((r) => r.mapperPatterns));
    this.userWatch = config.watch === null ? null : new GlobSet(config.watch);
    this.skip = new GlobSet(config.skip);
    this.testRules = rules.map((r) => ({
      codeExts: new Set(r.codeExts),
      patterns: new GlobSet(r.testPatterns)
    }));
    this.javaTests = config.languages.includes("java") ? new GlobSet(LANGUAGES.java.testPatterns) : null;
  }
  config;
  prunedDirs;
  buildOutputDirs;
  codeExtSet;
  roots;
  mappers;
  userWatch;
  skip;
  testRules;
  javaTests;
  // A directory prefix of `path` (its first `n` segments) is a source root.
  rootEndsBefore(segments, n) {
    for (let j = 1; j <= n; j++) {
      if (this.roots.matches(segments.slice(0, j).join("/"))) return true;
    }
    return false;
  }
  underRoot(path) {
    const segments = path.split("/");
    return this.rootEndsBefore(segments, segments.length - 1);
  }
  // A build-output directory name comes before any source-root prefix.
  inBuildOutput(path) {
    const segments = path.split("/");
    for (let i = 0; i < segments.length - 1; i++) {
      if (this.buildOutputDirs.has(segments[i]) && !this.rootEndsBefore(segments, i)) {
        return true;
      }
    }
    return false;
  }
  matchesMapperGlob(path) {
    return this.mappers.matches(path);
  }
  // Test patterns apply to each language's own code extensions; mapper XML
  // takes only the java test patterns.
  isTest(path) {
    const ext = extensionOf(path);
    if (this.testRules.some((t) => t.codeExts.has(ext) && t.patterns.matches(path))) {
      return true;
    }
    return this.javaTests !== null && this.matchesMapperGlob(path) && this.javaTests.matches(path);
  }
  inDefaultWatch(path) {
    if (hasDotSegment(path) || this.inBuildOutput(path)) return false;
    const ext = extensionOf(path);
    const { languages } = this.config;
    if (languages.includes("js") && LANGUAGES.js.codeExts.includes(ext)) return true;
    if (languages.includes("java")) {
      if (LANGUAGES.java.codeExts.includes(ext) && this.underRoot(path)) return true;
      if (this.matchesMapperGlob(path)) return true;
    }
    return false;
  }
  // Not watched for a reason the config states: skip, home, or a test file.
  excluded(path) {
    return this.skip.matches(path) || isUnder(path, this.config.home) || !this.config.tests && this.isTest(path);
  }
  isWatched(path) {
    const inWatch = this.userWatch ? this.userWatch.matches(path) : this.inDefaultWatch(path);
    return inWatch && !this.excluded(path);
  }
  // Code the language rules recognise but the watch set does not hold.
  isUnwatched(path) {
    if (hasDotSegment(path) || this.inBuildOutput(path)) return false;
    if (!this.codeExtSet.has(extensionOf(path)) && !this.matchesMapperGlob(path)) return false;
    return !this.isWatched(path) && !this.excluded(path);
  }
};
function measure(repo, config, plan) {
  const rules = new Rules(config);
  const files = listFiles(repo, rules.prunedDirs);
  const patterns = plan.flatMap(
    (part) => part.paths.map((p) => ({ id: part.id, pattern: p, regex: compileGlob(p), strength: strength(p) }))
  );
  const totals = new Map(plan.map((p) => [p.id, { files: 0, lines: 0 }]));
  const orphans = [];
  const contested = [];
  const unwatched = [];
  const owners = /* @__PURE__ */ new Map();
  const wonBy = /* @__PURE__ */ new Map();
  let watchedFiles = 0;
  for (const path of files) {
    if (!rules.isWatched(path)) {
      if (rules.isUnwatched(path)) unwatched.push(path);
      continue;
    }
    watchedFiles++;
    let best = -1;
    let matched = /* @__PURE__ */ new Map();
    for (const p of patterns) {
      if (!p.regex.test(path)) continue;
      if (p.strength > best) {
        best = p.strength;
        matched = /* @__PURE__ */ new Map([[p.id, p.pattern]]);
      } else if (p.strength === best && !matched.has(p.id)) {
        matched.set(p.id, p.pattern);
      }
    }
    if (matched.size === 0) {
      orphans.push(path);
      owners.set(path, null);
      continue;
    }
    const ids = [...matched.keys()].sort(byCodeUnit);
    if (ids.length > 1) contested.push({ path, parts: ids });
    owners.set(path, ids[0]);
    wonBy.set(path, matched.get(ids[0]));
    const total = totals.get(ids[0]);
    total.files++;
    total.lines += lineTotal(readFileSync2(join2(repo, path)));
  }
  return {
    parts: plan.map((p) => ({ id: p.id, ...totals.get(p.id) })),
    orphans,
    contested,
    unwatched,
    watchedFiles,
    owners,
    wonBy
  };
}

// src/draft.ts
var DraftError = class extends Error {
};
var BUILD_MARKERS = /* @__PURE__ */ new Set(["pom.xml", "build.gradle", "build.gradle.kts"]);
var MAIN_ROOTS = ["src/main/java", "src/main/kotlin"];
var TEST_ROOTS = ["src/test/java", "src/test/kotlin"];
var ROOT_FORM = /^(?:(.*)\/)?src\/(?:main|test)\/(?:java|kotlin)$/;
var MAPPER_RELATIVE = new GlobSet(["**/mapper/**/*.xml", "**/*Mapper.xml"]);
var MAPPER_NS_ATTR = /<mapper\b[^>]*?\bnamespace\s*=\s*(?:"([^"]*)"|'([^']*)')/;
var LAYER_NAMES = /* @__PURE__ */ new Set([
  "controller",
  "dao",
  "domain",
  "dto",
  "entity",
  "mapper",
  "model",
  "repository",
  "service",
  "vo"
]);
var segs = (p) => p === "" ? [] : p.split("/");
var dirOf = (p) => {
  const i = p.lastIndexOf("/");
  return i < 0 ? "" : p.slice(0, i);
};
var baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);
var stem = (name) => {
  const i = name.lastIndexOf(".");
  return i <= 0 ? name : name.slice(0, i);
};
var joinPath = (a, b) => a === "" ? b : b === "" ? a : `${a}/${b}`;
var isAncestor = (dir, path) => dir === "" ? path !== "" : isUnder(path, dir);
var strip = (path, dir) => dir === "" ? path : path.slice(dir.length + 1);
var sorted = (items) => [...new Set(items)].sort(byCodeUnit);
function asGlobLiteral(path) {
  return path.replace(/[*?[{]/g, (c) => `[${c}]`);
}
function normalizeId(text) {
  const id = text.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  return id === "" ? "top" : id;
}
function isRealDir(repo, path) {
  try {
    return lstatSync(join3(repo, path), { throwIfNoEntry: false })?.isDirectory() ?? false;
  } catch {
    return false;
  }
}
function newPart(kind, fields = {}) {
  return {
    kind,
    module: null,
    common: false,
    modulePart: false,
    path: "",
    files: [],
    received: false,
    moduleContent: false,
    dirPattern: false,
    exactPaths: false,
    topDirs: /* @__PURE__ */ new Set(),
    alive: true,
    forward: null,
    id: "",
    mapperFiles: [],
    extra: [],
    ...fields
  };
}
var linesOf = (files) => files.reduce((sum, f) => sum + f.lines, 0);
var moduleName = (m) => m.path === "" ? "." : m.path;
function compareParts(a, b) {
  if (a.kind !== b.kind) return a.kind - b.kind;
  if (a.kind === 1) {
    const byModule = byCodeUnit(moduleName(a.module), moduleName(b.module));
    if (byModule !== 0) return byModule;
  }
  return byCodeUnit(a.path, b.path);
}
function readRawConfig(repo) {
  const file = join3(repo, CONFIG_FILE);
  return existsSync2(file) ? JSON.parse(readFileSync3(file, "utf8")) : {};
}
function checkRoot(repo, root, where) {
  const parts = root.split("/").filter((s) => s !== "" && s !== ".");
  if (root.startsWith("/") || parts.length === 0 || parts.includes("..")) {
    throw new DraftError(`${where} "${root}" is not a directory inside the repository`);
  }
  const path = parts.join("/");
  if (!isRealDir(repo, path)) {
    throw new DraftError(`${where} "${root}" is not a directory inside the repository`);
  }
  return path;
}
function literalRoot(value) {
  let out = "";
  for (let i = 0; i < value.length; i++) {
    const c = value[i];
    if (c === "[" && value[i + 2] === "]") {
      out += value[i + 1];
      i += 2;
    } else if ("*?[{".includes(c)) {
      throw new DraftError(
        `roots in ${CONFIG_FILE} "${value}" is a glob; draft reads each root as one directory and does not expand globs, because it does not look at build files for given roots`
      );
    } else {
      out += c;
    }
  }
  return out;
}
function checkRoots(repo, values, where, read) {
  const roots = values.map((v) => checkRoot(repo, read(v), where));
  const repeated = roots.find((r, i) => roots.indexOf(r) !== i);
  if (repeated !== void 0) throw new DraftError(`${where} names "${repeated}" more than once`);
  return sorted(roots);
}
function chooseLanguages(repo, options, config, raw) {
  if (options.languages !== null) {
    const known = Object.keys(LANGUAGES);
    for (const name of options.languages) {
      if (!known.includes(name)) {
        throw new DraftError(`unknown language "${name}"; known: ${known.sort().join(", ")}`);
      }
    }
    if (new Set(options.languages).size !== options.languages.length) {
      throw new DraftError("--language names a language more than once");
    }
    return { languages: sorted(options.languages), source: "command line" };
  }
  if (raw.languages !== void 0) return { languages: config.languages, source: "codeplan.json" };
  const detected = Object.keys(LANGUAGES).filter((l) => LANGUAGES[l].detect.some((f) => existsSync2(join3(repo, f)))).sort(byCodeUnit);
  if (detected.length > 0) return { languages: detected, source: "detected" };
  return { languages: ["js"], source: "default" };
}
function findModules(repo, files, given, tests, rules) {
  const make = (path, roots, resources) => ({
    path,
    roots: sorted(roots),
    resources,
    code: [],
    mappers: [],
    commonPackage: "",
    start: null
  });
  if (given !== null) {
    const perDir = /* @__PURE__ */ new Map();
    for (const root of given) {
      const match = ROOT_FORM.exec(root);
      const dir = match ? match[1] ?? "" : root;
      const entry = perDir.get(dir) ?? { roots: [], dForm: false };
      entry.roots.push(root);
      entry.dForm ||= match !== null;
      perDir.set(dir, entry);
    }
    return sorted(perDir.keys()).map((dir) => {
      const { roots, dForm } = perDir.get(dir);
      const resources = joinPath(dir, "src/main/resources");
      return make(dir, roots, dForm && isRealDir(repo, resources) ? resources : null);
    });
  }
  const candidates = sorted(files.filter((f) => BUILD_MARKERS.has(baseOf(f))).map(dirOf));
  const modules = [];
  for (const dir of candidates) {
    if (hasDotSegment(dir)) continue;
    if (dir !== "" && rules.inBuildOutput(`${dir}/x`)) continue;
    const rootNames = tests ? [...MAIN_ROOTS, ...TEST_ROOTS] : MAIN_ROOTS;
    const roots = rootNames.map((r) => joinPath(dir, r)).filter((r) => isRealDir(repo, r));
    const resourcesPath = joinPath(dir, "src/main/resources");
    const resources = isRealDir(repo, resourcesPath) ? resourcesPath : null;
    const hasMapper = resources !== null && files.some((f) => isUnder(f, resources) && isMapperXml(repo, f, strip(f, resources)) !== null);
    if (roots.length === 0 && !hasMapper) continue;
    modules.push(make(dir, roots, resources));
  }
  return modules;
}
var namespaces = /* @__PURE__ */ new Map();
function readNamespace(repo, path) {
  const key = join3(repo, path);
  if (!namespaces.has(key)) {
    const match = MAPPER_NS_ATTR.exec(readFileSync3(key, "utf8"));
    const value = match ? match[1] ?? match[2] ?? "" : "";
    namespaces.set(key, value.trim() === "" ? null : value.trim());
  }
  return namespaces.get(key);
}
function isMapperXml(repo, path, rel) {
  if (extensionOf(path) !== "xml" || hasDotSegment(rel)) return null;
  const namespace = readNamespace(repo, path);
  if (MAPPER_RELATIVE.matches(rel)) return { outsidePattern: false, namespace };
  return namespace === null ? null : { outsidePattern: true, namespace };
}
function commonPackage(rels) {
  let cp = "";
  for (; ; ) {
    let hasFile = false;
    const subdirs = /* @__PURE__ */ new Set();
    for (const rel of rels) {
      const rest = segs(strip(rel, cp));
      if (rest.length === 1) hasFile = true;
      else subdirs.add(rest[0]);
    }
    if (hasFile || subdirs.size !== 1) return cp;
    cp = joinPath(cp, [...subdirs][0]);
  }
}
var keysAtDepth = (dirs) => (d) => new Set(dirs.map((dir) => segs(dir).slice(0, d).join("/")));
function startDepth(at, maxParts) {
  let depth = 1;
  for (; ; ) {
    const current = at(depth);
    const next = at(depth + 1);
    const same = next.size === current.size && [...next].every((k) => current.has(k));
    if (next.size > maxParts || same) return depth;
    depth++;
  }
}
function groupAtDepth(items, dirOfItem, depth) {
  const groups = /* @__PURE__ */ new Map();
  for (const item of items) {
    const key = segs(dirOfItem(item)).slice(0, depth).join("/");
    groups.set(key, [...groups.get(key) ?? [], item]);
  }
  return groups;
}
function draft(repo, options) {
  namespaces.clear();
  const config = readConfig(repo);
  const raw = readRawConfig(repo);
  const { maxParts } = options;
  const { languages, source: languageSource } = chooseLanguages(repo, options, config, raw);
  let given = null;
  let rootSource;
  if (options.roots !== null) {
    given = checkRoots(repo, options.roots, "--root", (r) => r);
    rootSource = "command line";
  } else if (raw.roots !== void 0) {
    given = checkRoots(repo, config.roots, `roots in ${CONFIG_FILE}`, literalRoot);
    rootSource = "codeplan.json";
  }
  const java = languages.includes("java");
  const codeExts = sorted(languages.flatMap((l) => LANGUAGES[l].codeExts));
  const codeExtSet = new Set(codeExts);
  const languageRoots = languages.flatMap((l) => [
    ...LANGUAGES[l].mainRootGlobs,
    ...config.tests ? LANGUAGES[l].testRootGlobs : []
  ]);
  const rules = new Rules({
    ...config,
    languages,
    watch: null,
    roots: given !== null ? given.map(asGlobLiteral) : languageRoots
  });
  const all = listFiles(repo, rules.prunedDirs);
  const modules = java ? findModules(repo, all, given, config.tests, rules) : [];
  const moduleCount = modules.length;
  const many = moduleCount > maxParts;
  const rootIndex = modules.flatMap((m) => m.roots.map((root) => ({ root, module: m }))).sort((a, b) => b.root.length - a.root.length);
  const resourcesIndex = modules.filter((m) => m.resources !== null).sort((a, b) => b.resources.length - a.resources.length);
  const languageRootWatch = many && given === null ? languageRoots.flatMap((r) => codeExts.map((e) => `${r}/**/*.${e}`)) : [];
  const languageRootSet = new GlobSet(languageRootWatch);
  const lineCount = (path) => lineTotal(readFileSync3(join3(repo, path)));
  const collected = [];
  const outside = [];
  for (const path of all) {
    const ext = extensionOf(path);
    const inRoot = codeExtSet.has(ext) ? rootIndex.find((r) => isUnder(path, r.root)) : void 0;
    if (inRoot) {
      const rel = strip(path, inRoot.root);
      if (hasDotSegment(rel) || rules.excluded(path)) continue;
      const rec = {
        path,
        lines: lineCount(path),
        module: inRoot.module,
        root: inRoot.root,
        rel,
        namespace: null,
        outsidePattern: false
      };
      inRoot.module.code.push(rec);
      collected.push(rec);
      continue;
    }
    const inResources = resourcesIndex.find((m) => isUnder(path, m.resources));
    if (inResources) {
      const rel = strip(path, inResources.resources);
      const mapper = isMapperXml(repo, path, rel);
      if (mapper !== null) {
        if (rules.excluded(path)) continue;
        const rec = {
          path,
          lines: lineCount(path),
          module: inResources,
          root: null,
          rel,
          ...mapper
        };
        inResources.mappers.push(rec);
        collected.push(rec);
        continue;
      }
    }
    const byDefault = rules.isWatched(path) || rules.isUnwatched(path);
    if (byDefault || languageRootSet.matches(path) && !rules.excluded(path)) {
      const rec = {
        path,
        lines: lineCount(path),
        module: null,
        root: null,
        rel: path,
        namespace: null,
        outsidePattern: false
      };
      outside.push(rec);
      collected.push(rec);
    }
  }
  const underGiven = (path) => given !== null && given.some((r) => isUnder(path, r));
  const fromBuildOutput = collected.filter(
    (f) => rules.inBuildOutput(f.path) && !underGiven(f.path)
  ).length;
  for (const m of modules) m.commonPackage = commonPackage(m.code.map((f) => f.rel));
  const parts = [];
  const outsideGroup = [];
  if (many) {
    const nonRoot = modules.filter((m) => m.path !== "").sort((a, b) => b.path.length - a.path.length);
    const extraFiles = /* @__PURE__ */ new Map();
    for (const f of outside) {
      const home = f.path.includes("/") ? nonRoot.find((m) => isUnder(f.path, m.path)) : void 0;
      if (home) extraFiles.set(home, [...extraFiles.get(home) ?? [], f]);
      else outsideGroup.push(f);
    }
    for (const m of modules) {
      const extra = extraFiles.get(m) ?? [];
      if (m.code.length + m.mappers.length + extra.length === 0) continue;
      const part = newPart(1, {
        module: m,
        modulePart: true,
        files: [...m.code, ...extra],
        dirPattern: extra.length > 0
      });
      m.start = part;
      parts.push(part);
    }
  } else {
    outsideGroup.push(...outside);
    for (const m of modules) {
      if (m.code.length === 0) {
        if (m.mappers.length > 0) {
          const part = newPart(1, { module: m, common: true });
          m.start = part;
          parts.push(part);
        }
        continue;
      }
      const dirFor = (f) => dirOf(strip(f.rel, m.commonPackage));
      const depth = startDepth(keysAtDepth(m.code.map(dirFor)), maxParts);
      for (const [key, files] of groupAtDepth(m.code, dirFor, depth)) {
        const part = newPart(1, { module: m, common: key === "", path: key, files });
        if (part.common) m.start = part;
        parts.push(part);
      }
    }
  }
  if (outsideGroup.length > 0) {
    const resourcesDirOf = (f) => resourcesIndex.find((m) => isUnder(f.path, m.resources))?.resources ?? null;
    const keyAt = (f, d) => resourcesDirOf(f) ?? segs(dirOf(f.path)).slice(0, d).join("/");
    const depth = startDepth((d) => new Set(outsideGroup.map((f) => keyAt(f, d))), maxParts);
    const groups = /* @__PURE__ */ new Map();
    for (const f of outsideGroup) {
      const key = keyAt(f, depth);
      groups.set(key, [...groups.get(key) ?? [], f]);
    }
    for (const [key, files] of groups) {
      if (key === "") parts.push(newPart(0, { files }));
      else parts.push(newPart(2, { path: key, files, exactPaths: resourcesDirOf(files[0]) === key }));
    }
  }
  const alive = () => parts.filter((p) => p.alive);
  const place = (p) => p.modulePart ? p.module.path : p.path;
  const levelOf = (p) => p.modulePart ? 0 : segs(p.path).length;
  const topHasAll = (top) => top.moduleContent || moduleCount === 0 && top.received;
  const sameSpace = (a, x) => {
    if (many) return !(a.modulePart && a.module.path === "");
    return x.kind === 1 ? a.kind === 1 && a.module === x.module : a.kind !== 1;
  };
  const covers = (a, x) => {
    if (a.kind === 2) return true;
    if (a.kind === 0) return topHasAll(a) || a.topDirs.has(segs(place(x))[0] ?? "");
    if (a.modulePart) return true;
    return !a.common || a.received;
  };
  const add = (part) => {
    parts.push(part);
    return part;
  };
  const topOrNew = () => alive().find((p) => p.kind === 0) ?? add(newPart(0));
  const upperPartOf = (x) => {
    const up = dirOf(place(x));
    if (!many && x.kind === 1) {
      const m = x.module;
      const found = alive().find((p) => p.kind === 1 && p.module === m && p.path === up);
      if (found) return found;
      const created = add(newPart(1, { module: m, common: up === "", path: up }));
      if (created.common) m.start = created;
      return created;
    }
    if (up === "") return topOrNew();
    return alive().find((p) => p.kind !== 0 && sameSpace(p, x) && place(p) === up) ?? add(newPart(2, { path: up }));
  };
  const target = (x) => {
    if (x.common) return topOrNew();
    let best = null;
    for (const a of alive()) {
      if (a === x || !sameSpace(a, x) || !isAncestor(place(a), place(x)) || !covers(a, x)) continue;
      if (best === null || place(a).length > place(best).length) best = a;
    }
    return best ?? upperPartOf(x);
  };
  const merge = (x, into) => {
    into.files.push(...x.files);
    into.received = true;
    if (x.kind === 1 || x.moduleContent) into.moduleContent = true;
    if (into.kind === 0 && x.kind === 2) into.topDirs.add(segs(x.path)[0]);
    if (into.modulePart) into.dirPattern = true;
    x.alive = false;
    x.forward = into;
  };
  const eligible = () => {
    const now = alive();
    if (many) {
      const moduleParts = now.filter((p) => p.modulePart && p.module.path !== "");
      const dirs = now.filter((p) => p.kind === 2 && (!p.received || moduleParts.length === 0));
      return [...moduleParts, ...dirs];
    }
    const early = now.filter((p) => p.kind === 2 || p.kind === 1 && !p.common);
    if (early.length > 0 || moduleCount !== 1) return early;
    return now.filter((p) => p.common);
  };
  const foldOrder = (a, b) => linesOf(a.files) - linesOf(b.files) || levelOf(b) - levelOf(a) || compareParts(a, b);
  while (alive().length > maxParts) {
    const candidates = eligible();
    if (candidates.length === 0) break;
    const x = candidates.sort(foldOrder)[0];
    merge(x, target(x));
  }
  const final = alive().sort(compareParts);
  const notes = [];
  const used = /* @__PURE__ */ new Set();
  for (const p of final) {
    let base;
    if (p.kind === 0) base = "top";
    else if (p.kind === 2) base = normalizeId(p.path);
    else {
      const m = p.module;
      const name = m.path === "" ? "top" : normalizeId(m.path);
      if (p.modulePart) base = name;
      else if (moduleCount >= 2) base = p.common ? name : normalizeId(`${name}-${p.path}`);
      else if (p.common) base = m.commonPackage === "" ? name : normalizeId(segs(m.commonPackage).at(-1));
      else base = normalizeId(p.path);
    }
    let id = base;
    for (let n = 2; used.has(id); n++) id = `${base}-${n}`;
    used.add(id);
    p.id = id;
  }
  const zoneOf = (p) => {
    if (moduleCount === 0) return p.kind === 0 ? void 0 : segs(p.path)[0];
    if (moduleCount === 1) return void 0;
    if (!many) return p.kind === 1 ? p.module.path === "" ? "top" : p.module.path : void 0;
    if (p.modulePart) return p.module.path === "" ? "top" : segs(p.module.path)[0];
    return p.kind === 2 ? segs(p.path)[0] : void 0;
  };
  const resolve2 = (p) => p.forward ? resolve2(p.forward) : p;
  const ownerOf = /* @__PURE__ */ new Map();
  for (const p of final) for (const f of p.files) ownerOf.set(f.path, p);
  const codeLines = (p) => linesOf(p.files);
  const mappers = {
    namespace: 0,
    fileName: 0,
    package: 0,
    rest: 0,
    restFiles: [],
    outsidePattern: 0
  };
  for (const m of modules) {
    if (m.mappers.length === 0) continue;
    const packageParts = final.filter((p) => p.kind === 1 && !p.modulePart && p.module === m).map((p) => ({ part: p, pkg: joinPath(m.commonPackage, p.path) })).filter((x) => x.pkg !== "");
    const found = /* @__PURE__ */ new Map();
    for (const xml of m.mappers) {
      if (xml.outsidePattern) mappers.outsidePattern++;
      if (xml.namespace !== null) {
        const cls = xml.namespace.split(".").join("/");
        const file = m.code.find((f) => f.rel === `${cls}.java` || f.rel === `${cls}.kt`);
        if (file) {
          found.set(xml, ownerOf.get(file.path));
          mappers.namespace++;
          continue;
        }
      }
      const sameName = m.code.filter(
        (f) => ["java", "kt"].includes(extensionOf(f.path)) && stem(baseOf(f.path)) === stem(baseOf(xml.path))
      );
      if (sameName.length === 1) {
        found.set(xml, ownerOf.get(sameName[0].path));
        mappers.fileName++;
        continue;
      }
      const dir = dirOf(xml.rel);
      let byPackage = null;
      for (const x of packageParts) {
        if ((dir === x.pkg || isUnder(dir, x.pkg)) && (!byPackage || x.pkg.length > byPackage.pkg.length)) {
          byPackage = x;
        }
      }
      if (byPackage) {
        found.set(xml, byPackage.part);
        mappers.package++;
      }
    }
    const best = (candidates, counted) => {
      const count = (p) => counted.filter((x) => found.get(x) === p).length;
      const ranked = [...new Set(candidates)].sort(
        (a, b) => count(b) - count(a) || codeLines(b) - codeLines(a) || byCodeUnit(a.id, b.id)
      );
      return ranked[0] ?? null;
    };
    const representative = m.start !== null ? resolve2(m.start) : best(m.code.map((f) => ownerOf.get(f.path)), m.mappers);
    const perDir = /* @__PURE__ */ new Map();
    for (const xml of m.mappers) {
      const dir = dirOf(xml.rel);
      perDir.set(dir, [...perDir.get(dir) ?? [], xml]);
    }
    const take = (part, xml) => {
      part.mapperFiles.push(xml);
      if (!found.has(xml)) {
        mappers.rest++;
        mappers.restFiles.push(xml.path);
      }
    };
    for (const dir of sorted(perDir.keys())) {
      const xmls = perDir.get(dir);
      if (dir === "") {
        for (const xml of xmls) {
          const owner = found.get(xml) ?? representative;
          owner.extra.push(asGlobLiteral(xml.path));
          take(owner, xml);
        }
        continue;
      }
      const taken = xmls.filter((x) => found.has(x));
      const holder = best(taken.map((x) => found.get(x)), taken) ?? representative;
      holder.extra.push(`${asGlobLiteral(joinPath(m.resources, dir))}/*.xml`);
      for (const xml of xmls) {
        const owner = found.get(xml) ?? holder;
        if (owner !== holder) owner.extra.push(asGlobLiteral(xml.path));
        take(owner, xml);
      }
    }
    const prefix = m.path === "" ? "" : `${asGlobLiteral(m.path)}/`;
    representative.extra.push(
      `${prefix}src/main/resources/**/mapper/**/*.xml`,
      `${prefix}src/main/resources/**/*Mapper.xml`
    );
  }
  mappers.restFiles.sort(byCodeUnit);
  const pathsOf = (p) => {
    const globsUnderRoots = (files, pkg) => sorted(files.filter((f) => f.root !== null).map((f) => f.root)).map(
      (root) => `${asGlobLiteral(joinPath(root, pkg))}/**`
    );
    let own;
    if (p.kind === 0) {
      own = topHasAll(p) ? ["**"] : [
        ...[...p.topDirs].map((d) => `${asGlobLiteral(d)}/**`),
        ...p.files.filter((f) => !f.path.includes("/")).map((f) => asGlobLiteral(f.path))
      ];
    } else if (p.kind === 2) {
      own = p.exactPaths && !p.received ? p.files.map((f) => asGlobLiteral(f.path)) : [`${asGlobLiteral(p.path)}/**`];
    } else if (p.modulePart) {
      own = p.dirPattern ? [`${asGlobLiteral(p.module.path)}/**`] : globsUnderRoots(p.files, "");
    } else if (p.common && !p.received) {
      own = p.files.map((f) => asGlobLiteral(f.path));
    } else {
      own = globsUnderRoots(p.files, joinPath(p.module.commonPackage, p.path));
    }
    const all2 = sorted([...own, ...p.extra]);
    const wide = all2.filter((q) => q === "**" || q.endsWith("/**")).map((q) => q.slice(0, -2));
    return all2.filter((q) => !wide.some((w) => q !== `${w}**` && q.startsWith(w)));
  };
  const draftConfig = { languages };
  if (java && collected.length > 0) {
    const watch = [];
    const forRoot = (root) => codeExts.map((e) => `${root}/**/*.${e}`);
    if (moduleCount > 0) {
      if (!many) {
        for (const m of modules) {
          for (const root of sorted(m.code.map((f) => f.root))) watch.push(...forRoot(asGlobLiteral(root)));
        }
      } else if (given !== null) {
        for (const root of given) watch.push(...forRoot(asGlobLiteral(root)));
      } else {
        watch.push(...languageRootWatch);
      }
      for (const m of modules) {
        if (m.mappers.length === 0) continue;
        const prefix = m.path === "" ? "" : `${asGlobLiteral(m.path)}/`;
        watch.push(
          `${prefix}src/main/resources/**/mapper/**/*.xml`,
          `${prefix}src/main/resources/**/*Mapper.xml`
        );
        const outsidePattern = m.mappers.filter((x) => x.outsidePattern);
        for (const dir of sorted(outsidePattern.map((x) => dirOf(x.path)))) {
          const others = all.some((f) => {
            if (dirOf(f) !== dir || extensionOf(f) !== "xml" || baseOf(f).startsWith(".")) return false;
            return isMapperXml(repo, f, strip(f, m.resources)) === null;
          });
          if (others) {
            for (const x of outsidePattern) if (dirOf(x.path) === dir) watch.push(asGlobLiteral(x.path));
          } else {
            watch.push(`${asGlobLiteral(dir)}/*.xml`);
          }
        }
      }
    }
    for (const f of outside) watch.push(asGlobLiteral(f.path));
    draftConfig.watch = sorted(watch);
    if (outside.length > 0) {
      notes.push({
        code: "exact-paths",
        files: outside.length,
        message: `${outside.length} files outside modules are listed in config.watch by exact path; a directory pattern can replace them`
      });
    }
  }
  if (given !== null) draftConfig.roots = sorted(given.map(asGlobLiteral));
  if (java && moduleCount === 1) {
    notes.push({ code: "no-zones", message: "one module, so no zone is proposed" });
  }
  if (final.length > maxParts) {
    notes.push({
      code: "over-max-parts",
      parts: final.length,
      maxParts,
      message: `${final.length} parts, more than the maximum ${maxParts}: ` + (many ? "the root module part cannot fold" : "parts do not fold across modules")
    });
  }
  if (many) {
    const remaining = final.filter((p) => p.modulePart).length;
    notes.push({
      code: "modules",
      modules: moduleCount,
      notRemaining: moduleCount - remaining,
      message: `${moduleCount} modules; ${moduleCount - remaining} of them do not remain as module parts`
    });
  }
  const javaCode = modules.flatMap(
    (m) => m.code.filter((f) => ["java", "kt"].includes(extensionOf(f.path))).map((f) => ({ f, m }))
  );
  const layerFiles = javaCode.filter(
    ({ f, m }) => segs(dirOf(strip(f.rel, m.commonPackage))).some((s) => LAYER_NAMES.has(s))
  ).length;
  if (javaCode.length > 0 && layerFiles * 2 >= javaCode.length) {
    notes.push({
      code: "layers",
      layerFiles,
      files: javaCode.length,
      message: `${layerFiles} of ${javaCode.length} Java/Kotlin files sit in layer directories (controller, service, ...); parts here follow packages, not layers`
    });
  }
  if (fromBuildOutput > 0) {
    notes.push({
      code: "build-output",
      files: fromBuildOutput,
      message: `${fromBuildOutput} files under build-output directory names are measured because config.watch reaches them`
    });
  }
  const draftParts = final.map((p) => {
    const zone = zoneOf(p);
    return {
      id: p.id,
      name: p.id,
      ...zone === void 0 ? {} : { zone },
      about: "",
      paths: pathsOf(p)
    };
  });
  const measured = final.map((p) => ({
    id: p.id,
    files: p.files.length + p.mapperFiles.length,
    lines: linesOf(p.files) + linesOf(p.mapperFiles)
  }));
  const result2 = {
    plan: { format: 1, name: "", about: "", parts: draftParts, externals: [], scenarios: [] },
    config: draftConfig,
    measure: {
      parts: measured,
      files: measured.reduce((s, p) => s + p.files, 0),
      lines: measured.reduce((s, p) => s + p.lines, 0)
    },
    sources: { languages: languageSource, ...rootSource ? { roots: rootSource } : {} },
    notes
  };
  if (java) {
    result2.modules = sorted(modules.map(moduleName));
    result2.mappers = mappers;
  }
  return result2;
}

// src/plan.ts
import { existsSync as existsSync3, readFileSync as readFileSync4 } from "node:fs";
import { join as join4 } from "node:path";
function planPath(home) {
  return `${home}/plan.json`;
}
function readPlanFile(repo, home) {
  const relative = planPath(home);
  const file = join4(repo, relative);
  if (!existsSync3(file)) return { kind: "missing" };
  try {
    return { kind: "json", value: JSON.parse(readFileSync4(file, "utf8")) };
  } catch (e) {
    return { kind: "not-json", message: `${relative} is not valid JSON: ${e.message}` };
  }
}

// src/validate.ts
import { lstatSync as lstatSync2 } from "node:fs";
import { join as join5 } from "node:path";
var SEVERITY = {
  "bad-shape": "error",
  "repeated-id": "error",
  "no-paths": "error",
  "unknown-target": "error",
  "empty-part": "warning",
  "planned-has-files": "warning",
  "start-missing": "warning",
  "start-elsewhere": "warning",
  "self-use": "warning",
  "repeated-use": "warning",
  "unused-external": "warning",
  "short-scenario": "warning",
  "gap-in-scenario": "warning",
  "unknown-reference": "warning",
  "no-plan": "warning"
};
function diagnostic(code, at, message) {
  return { code, severity: SEVERITY[code], at, message };
}
var ID_FORM = /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/;
var REFERENCE = /\{([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\}/g;
var KNOWN = {
  plan: ["format", "name", "about", "parts", "externals", "scenarios", "note"],
  part: ["id", "name", "zone", "about", "start", "paths", "planned", "uses", "note"],
  use: ["to", "why", "evidence", "note"],
  external: ["id", "name", "about", "note"],
  scenario: ["id", "name", "steps", "note"],
  step: ["at", "does", "note"]
};
var isObj = (v) => typeof v === "object" && v !== null && !Array.isArray(v);
var validId = (v) => typeof v === "string" && ID_FORM.test(v);
var arrayOf = (v) => Array.isArray(v) ? v : [];
function goodPaths(paths) {
  return Array.isArray(paths) && paths.length > 0 && paths.every((p) => typeof p === "string" && p !== "");
}
function preparePlan(raw) {
  const prepared = {
    raw,
    parts: [],
    excluded: 0,
    repeatedItems: /* @__PURE__ */ new Set(),
    targets: /* @__PURE__ */ new Set(),
    usesOf: /* @__PURE__ */ new Map(),
    skipInGaps: /* @__PURE__ */ new Set(),
    used: /* @__PURE__ */ new Set(),
    brokenRelation: false
  };
  if (!isObj(raw)) return prepared;
  const parts = arrayOf(raw.parts);
  const externals = arrayOf(raw.externals);
  const scenarios = arrayOf(raw.scenarios);
  const seen = /* @__PURE__ */ new Set();
  for (const item of [...parts, ...externals, ...scenarios]) {
    if (!isObj(item) || !validId(item.id)) continue;
    if (seen.has(item.id)) prepared.repeatedItems.add(item);
    else seen.add(item.id);
  }
  const kept = (item) => isObj(item) && validId(item.id) && !prepared.repeatedItems.has(item);
  for (const item of [...parts, ...externals]) if (kept(item)) prepared.targets.add(item.id);
  for (const part of parts) {
    if (kept(part) && goodPaths(part.paths)) prepared.parts.push({ id: part.id, paths: part.paths, planned: part.planned === true });
    else prepared.excluded++;
    if (!isObj(part)) continue;
    if (prepared.repeatedItems.has(part)) prepared.skipInGaps.add(part.id);
    if (part.uses === void 0) continue;
    if (!Array.isArray(part.uses)) {
      prepared.brokenRelation = true;
      if (validId(part.id)) prepared.skipInGaps.add(part.id);
      continue;
    }
    for (const use of part.uses) {
      if (!isObj(use) || !validId(use.to) || !prepared.targets.has(use.to)) {
        prepared.brokenRelation = true;
        continue;
      }
      prepared.used.add(use.to);
      if (kept(part)) {
        const set = prepared.usesOf.get(part.id) ?? /* @__PURE__ */ new Set();
        set.add(use.to);
        prepared.usesOf.set(part.id, set);
      }
    }
  }
  for (const scenario of scenarios) {
    if (!isObj(scenario)) continue;
    if (!Array.isArray(scenario.steps)) {
      prepared.brokenRelation = true;
      continue;
    }
    for (const step of scenario.steps) {
      if (!isObj(step) || !validId(step.at) || !prepared.targets.has(step.at)) {
        prepared.brokenRelation = true;
        continue;
      }
      prepared.used.add(step.at);
    }
  }
  return prepared;
}
function diagnosePlan(prepared, facts) {
  const out = [];
  const emit = (code, at, message) => out.push(diagnostic(code, at, message));
  const raw = prepared.raw;
  if (!isObj(raw)) {
    emit("bad-shape", "plan", "the plan must be a JSON object");
    return out;
  }
  const measured = new Set(prepared.parts.map((p) => p.id));
  const missing = (at, who, key) => emit("bad-shape", at, `${who}\`${key}\` is required`);
  const string = (obj, key, at, who, required) => {
    if (!(key in obj)) {
      if (required) missing(at, who, key);
      return false;
    }
    if (typeof obj[key] !== "string") {
      emit("bad-shape", at, `${who}\`${key}\` must be a string`);
      return false;
    }
    return true;
  };
  const text = (obj, key, at, who, required, refs) => {
    if (!string(obj, key, at, who, required) || !refs) return;
    const named = /* @__PURE__ */ new Set();
    for (const match of obj[key].matchAll(REFERENCE)) {
      const id2 = match[1];
      if (prepared.targets.has(id2) || named.has(id2)) continue;
      named.add(id2);
      emit("unknown-reference", at, `${who}{${id2}} names no part or external`);
    }
  };
  const rest = (obj, at, who, known) => {
    const place = (key) => at === "" ? key : `${at}.${key}`;
    if ("note" in obj && typeof obj.note !== "string") {
      emit("bad-shape", place("note"), `${who}\`note\` must be a string`);
    }
    for (const key of Object.keys(obj).filter((k) => !known.includes(k)).sort(byCodeUnit)) {
      emit("bad-shape", place(key), `${who}unknown key \`${key}\``);
    }
  };
  const id = (obj, at, kind, repeated) => {
    if (!("id" in obj)) missing(`${at}.id`, "", "id");
    else if (!validId(obj.id)) {
      emit("bad-shape", `${at}.id`, `${kind} id must be lowercase letters, digits and inner hyphens`);
    } else if (repeated) {
      emit("repeated-id", `${at}.id`, `${kind} "${obj.id}": the id is used by an earlier item`);
    }
    return validId(obj.id) ? `${kind} "${obj.id}": ` : "";
  };
  if (raw.format !== 1) emit("bad-shape", "format", "`format` must be 1");
  string(raw, "name", "name", "", true);
  text(raw, "about", "about", "", true, true);
  if (!("parts" in raw)) missing("parts", "", "parts");
  else if (!Array.isArray(raw.parts)) emit("bad-shape", "parts", "`parts` must be an array");
  else raw.parts.forEach((part, i) => checkPart(part, `parts[${i}]`));
  if ("externals" in raw && !Array.isArray(raw.externals)) {
    emit("bad-shape", "externals", "`externals` must be an array");
  } else {
    arrayOf(raw.externals).forEach((external, i) => checkExternal(external, `externals[${i}]`));
  }
  if ("scenarios" in raw && !Array.isArray(raw.scenarios)) {
    emit("bad-shape", "scenarios", "`scenarios` must be an array");
  } else {
    arrayOf(raw.scenarios).forEach((scenario, i) => checkScenario(scenario, `scenarios[${i}]`));
  }
  rest(raw, "", "", KNOWN.plan);
  function checkPart(part, at) {
    if (!isObj(part)) {
      emit("bad-shape", at, "a part must be an object");
      return;
    }
    const repeated = prepared.repeatedItems.has(part);
    const who = id(part, at, "part", repeated);
    const partId = validId(part.id) ? part.id : null;
    const isMeasured = partId !== null && !repeated && measured.has(partId);
    const planned = part.planned === true;
    string(part, "name", `${at}.name`, who, true);
    string(part, "zone", `${at}.zone`, who, false);
    text(part, "about", `${at}.about`, who, true, !repeated);
    if ("start" in part) {
      const start = part.start;
      if (typeof start !== "string" || !isPathForm(start)) {
        emit("bad-shape", `${at}.start`, `${who}\`start\` must be a path inside the repository`);
      } else if (!repeated) {
        let isFile = false;
        try {
          isFile = lstatSync2(join5(facts.repo, start), { throwIfNoEntry: false })?.isFile() ?? false;
        } catch {
          isFile = false;
        }
        if (!isFile) {
          if (!planned) emit("start-missing", `${at}.start`, `${who}${start} is not a file in the repository`);
        } else if (isMeasured && facts.owners.has(start) && facts.owners.get(start) !== partId) {
          emit("start-elsewhere", `${at}.start`, `${who}${start} is not measured in this part`);
        }
      }
    }
    const paths = part.paths;
    if (Array.isArray(paths) && paths.length > 0) {
      paths.forEach((p, j) => {
        if (typeof p !== "string" || p === "") {
          emit("bad-shape", `${at}.paths[${j}]`, `${who}a pattern must be a non-empty string`);
        }
      });
      if (isMeasured && !planned && facts.files.get(partId) === 0) {
        emit("empty-part", `${at}.paths`, `${who}its paths match no watched file`);
      }
    } else if (paths !== void 0 && !Array.isArray(paths)) {
      emit("bad-shape", `${at}.paths`, `${who}\`paths\` must be an array of patterns`);
    } else if (!repeated) {
      emit("no-paths", `${at}.paths`, `${who}a part needs at least one pattern in \`paths\``);
    }
    if ("planned" in part && typeof part.planned !== "boolean") {
      emit("bad-shape", `${at}.planned`, `${who}\`planned\` must be true or false`);
    } else if (planned && isMeasured && facts.files.get(partId) > 0) {
      emit(
        "planned-has-files",
        `${at}.planned`,
        `${who}the part is planned, but ${facts.files.get(partId)} watched files are measured in it`
      );
    }
    if ("uses" in part) {
      if (!Array.isArray(part.uses)) {
        emit("bad-shape", `${at}.uses`, `${who}\`uses\` must be an array`);
      } else {
        const seenTo = /* @__PURE__ */ new Set();
        part.uses.forEach((use, k) => {
          const uat = `${at}.uses[${k}]`;
          if (!isObj(use)) {
            emit("bad-shape", uat, `${who}a use must be an object`);
            return;
          }
          if (!("to" in use)) missing(`${uat}.to`, who, "to");
          else if (!validId(use.to)) {
            emit("bad-shape", `${uat}.to`, `${who}\`to\` must be an id`);
          } else if (!repeated) {
            const to = use.to;
            if (to === partId) emit("self-use", `${uat}.to`, `${who}the part uses itself`);
            if (seenTo.has(to)) emit("repeated-use", `${uat}.to`, `${who}uses "${to}" more than once`);
            seenTo.add(to);
            if (!prepared.targets.has(to)) {
              emit("unknown-target", `${uat}.to`, `${who}"${to}" is not a part or external`);
            }
          }
          text(use, "why", `${uat}.why`, who, false, !repeated);
          if ("evidence" in use) {
            if (!Array.isArray(use.evidence)) {
              emit("bad-shape", `${uat}.evidence`, `${who}\`evidence\` must be an array of strings`);
            } else {
              use.evidence.forEach((e, j) => {
                if (typeof e !== "string") {
                  emit("bad-shape", `${uat}.evidence[${j}]`, `${who}evidence must be a string`);
                }
              });
            }
          }
          rest(use, uat, who, KNOWN.use);
        });
      }
    }
    rest(part, at, who, KNOWN.part);
  }
  function checkExternal(external, at) {
    if (!isObj(external)) {
      emit("bad-shape", at, "an external must be an object");
      return;
    }
    const repeated = prepared.repeatedItems.has(external);
    if (validId(external.id) && !repeated && prepared.repeatedItems.size === 0 && !prepared.brokenRelation && !prepared.used.has(external.id)) {
      emit("unused-external", at, `external "${external.id}": no use or step names it`);
    }
    const who = id(external, at, "external", repeated);
    string(external, "name", `${at}.name`, who, true);
    text(external, "about", `${at}.about`, who, true, !repeated);
    rest(external, at, who, KNOWN.external);
  }
  function checkScenario(scenario, at) {
    if (!isObj(scenario)) {
      emit("bad-shape", at, "a scenario must be an object");
      return;
    }
    const repeated = prepared.repeatedItems.has(scenario);
    const who = id(scenario, at, "scenario", repeated);
    string(scenario, "name", `${at}.name`, who, true);
    if (!("steps" in scenario)) missing(`${at}.steps`, who, "steps");
    else if (!Array.isArray(scenario.steps)) {
      emit("bad-shape", `${at}.steps`, `${who}\`steps\` must be an array`);
    } else {
      const steps = scenario.steps;
      if (!repeated && steps.length < 2) {
        emit("short-scenario", `${at}.steps`, `${who}a scenario needs two steps or more`);
      }
      const known = (step) => isObj(step) && validId(step.at) && prepared.targets.has(step.at) && !prepared.skipInGaps.has(step.at) ? step.at : null;
      const linked = (a, b) => (prepared.usesOf.get(a)?.has(b) ?? false) || (prepared.usesOf.get(b)?.has(a) ?? false);
      steps.forEach((step, k) => {
        const sat = `${at}.steps[${k}]`;
        if (!isObj(step)) {
          emit("bad-shape", sat, `${who}a step must be an object`);
          return;
        }
        if (!("at" in step)) missing(`${sat}.at`, who, "at");
        else if (!validId(step.at)) emit("bad-shape", `${sat}.at`, `${who}\`at\` must be an id`);
        else if (!repeated) {
          if (!prepared.targets.has(step.at)) {
            emit("unknown-target", `${sat}.at`, `${who}"${step.at}" is not a part or external`);
          }
          if (k > 0) {
            const a = known(steps[k - 1]);
            const b = known(step);
            if (a !== null && b !== null && a !== b && !linked(a, b)) {
              emit("gap-in-scenario", `${sat}.at`, `${who}no use links "${a}" and "${b}" in either direction`);
            }
          }
        }
        text(step, "does", `${sat}.does`, who, true, !repeated);
        rest(step, sat, who, KNOWN.step);
      });
    }
    rest(scenario, at, who, KNOWN.scenario);
  }
  const first = /* @__PURE__ */ new Map();
  out.forEach((d, i) => {
    if (!first.has(d.at)) first.set(d.at, i);
  });
  return out.map((d, i) => ({ d, i })).sort((x, y) => first.get(x.d.at) - first.get(y.d.at) || byCodeUnit(x.d.code, y.d.code) || x.i - y.i).map((x) => x.d);
}

// src/summary.ts
var oneLine = (text) => text.replace(/\r\n|\n|\r/g, " ");
var shown = (item) => item.name === "" ? item.id : item.name;
var plain = (text) => oneLine(text).replace(/[\\`*_[\]<>|~&#]/g, "\\$&");
function lineStart(text) {
  if (/^[-+=]/.test(text)) return `\\${text}`;
  return text.replace(/^([0-9]+)([.)])/, "$1\\$2");
}
function nodeLabel(text) {
  return oneLine(text).replace(/["#%&<>`|]/g, (c) => `#${c.charCodeAt(0)};`);
}
function mermaidLines(value) {
  const plan = value;
  const node = /* @__PURE__ */ new Map();
  plan.parts.forEach((p, i) => node.set(p.id, `p${i}`));
  (plan.externals ?? []).forEach((e, i) => node.set(e.id, `x${i}`));
  const partLine = (p, indent) => `${indent}${node.get(p.id)}["${nodeLabel(shown(p))}"]`;
  const zoneOf = (p) => p.zone ?? "";
  const lines = ["flowchart LR"];
  const zones = [...new Set(plan.parts.map(zoneOf).filter((z) => z !== ""))].sort(byCodeUnit);
  zones.forEach((zone, i) => {
    lines.push(`  subgraph z${i}["${nodeLabel(zone)}"]`);
    for (const p of plan.parts) if (zoneOf(p) === zone) lines.push(partLine(p, "    "));
    lines.push("  end");
  });
  for (const p of plan.parts) if (zoneOf(p) === "") lines.push(partLine(p, "  "));
  for (const e of plan.externals ?? []) lines.push(`  ${node.get(e.id)}[("${nodeLabel(shown(e))}")]`);
  for (const p of plan.parts) {
    for (const use of p.uses ?? []) {
      const why = use.why ?? "";
      const arrow = why === "" ? "-->" : `-->|"${nodeLabel(why)}"|`;
      lines.push(`  ${node.get(p.id)} ${arrow} ${node.get(use.to)}`);
    }
  }
  const planned = plan.parts.filter((p) => p.planned === true).map((p) => node.get(p.id));
  if (planned.length > 0) {
    lines.push("  classDef planned stroke-dasharray: 5 5", `  class ${planned.join(",")} planned`);
  }
  return lines;
}
function mermaidText(value) {
  return `${mermaidLines(value).join("\n")}
`;
}
function summaryText(value, m) {
  const plan = value;
  const externals = plan.externals ?? [];
  const scenarios = plan.scenarios ?? [];
  const names = /* @__PURE__ */ new Map();
  for (const item of [...plan.parts, ...externals]) names.set(item.id, shown(item));
  const strong = (id) => `**${plain(names.get(id))}**`;
  const prose = (text) => {
    const line = oneLine(text);
    let out = "";
    let from = 0;
    for (const match of line.matchAll(REFERENCE)) {
      if (!names.has(match[1])) continue;
      out += plain(line.slice(from, match.index)) + strong(match[1]);
      from = match.index + match[0].length;
    }
    return out + plain(line.slice(from));
  };
  const paragraph = (text) => {
    const trimmed = oneLine(text).replace(/^[ \t]+|[ \t]+$/g, "");
    if (trimmed !== "") blocks.push(lineStart(prose(trimmed)));
  };
  const row = (cells) => `| ${cells.join(" | ")} |`;
  const blocks = [`# ${plan.name === "" ? "codeplan" : plain(plan.name)}`];
  paragraph(plan.about);
  blocks.push("## Parts");
  const counts = new Map(m.parts.map((p) => [p.id, p]));
  blocks.push(
    [
      "| Part | Id | Zone | Files | Lines |",
      "|---|---|---|---|---|",
      ...plan.parts.map((p) => {
        const count = counts.get(p.id);
        const [files, lines] = p.planned === true ? ["planned", "planned"] : [count.files, count.lines];
        return row([plain(shown(p)), p.id, plain(p.zone ?? ""), files, lines]);
      })
    ].join("\n")
  );
  if (m.orphans.length > 0) blocks.push(`${m.orphans.length} watched files belong to no part.`);
  if (m.unwatched.length > 0) blocks.push(`${m.unwatched.length} code files are outside the watch set.`);
  for (const p of plan.parts) {
    blocks.push(`### ${plain(shown(p))}`);
    paragraph(p.about);
    const uses = p.uses ?? [];
    if (uses.length > 0) {
      blocks.push("Uses:");
      blocks.push(
        uses.map((u) => `- ${strong(u.to)}${u.why === void 0 || u.why === "" ? "" : `: ${prose(u.why)}`}`).join("\n")
      );
    }
  }
  if (externals.length > 0) {
    blocks.push("## Externals");
    blocks.push(
      [
        "| External | Id | About |",
        "|---|---|---|",
        ...externals.map((e) => row([plain(shown(e)), e.id, prose(e.about)]))
      ].join("\n")
    );
  }
  if (scenarios.length > 0) {
    blocks.push("## Scenarios");
    for (const s of scenarios) {
      blocks.push(`### ${plain(shown(s))}`);
      if (s.steps.length > 0) {
        blocks.push(
          s.steps.map((step, k) => `${k + 1}. ${strong(step.at)}${step.does === "" ? "" : `: ${prose(step.does)}`}`).join("\n")
        );
      }
    }
  }
  blocks.push("## Diagram");
  blocks.push(["```mermaid", ...mermaidLines(plan), "```"].join("\n"));
  return `${blocks.join("\n\n")}
`;
}

// src/report.ts
var SNAPSHOT_FORMAT = 1;
var RULES = 1;
var LIST_LIMIT = 30;
function takeSnapshot(found) {
  const { config, measurement: m } = found;
  const counts = new Map(m.parts.map((p) => [p.id, p]));
  const contested = new Set(m.contested.map((c) => c.path));
  const complete = found.excluded === 0;
  return {
    snapshot: SNAPSHOT_FORMAT,
    rules: RULES,
    config: {
      languages: config.languages,
      watch: config.watch,
      skip: config.skip,
      roots: config.roots,
      tests: config.tests
    },
    plan: !found.hasPlan ? "missing" : found.diagnostics.some((d) => d.severity === "error") ? "invalid" : "ok",
    complete,
    page: found.pageState,
    parts: found.measured.map((p) => ({
      id: p.id,
      paths: p.paths,
      planned: p.planned === true,
      files: counts.get(p.id).files,
      lines: counts.get(p.id).lines
    })),
    owned: [...m.owners].filter(([path, part]) => part !== null && !contested.has(path)).map(([path, part]) => ({ path, part, pattern: m.wonBy.get(path) })).sort((a, b) => byCodeUnit(a.path, b.path)),
    orphans: complete ? [...m.orphans].sort(byCodeUnit) : [],
    contested: m.contested.map((c) => ({ path: c.path, parts: [...c.parts].sort(byCodeUnit) })).sort((a, b) => byCodeUnit(a.path, b.path)),
    unwatched: [...m.unwatched].sort(byCodeUnit),
    diagnostics: found.diagnostics.map((d) => ({
      at: d.at,
      code: d.code,
      severity: d.severity,
      message: d.message,
      fix: found.fix(d.code)
    }))
  };
}
function snapshotText(snapshot2) {
  return `${JSON.stringify(snapshot2, null, 2)}
`;
}
var isObj2 = (v) => typeof v === "object" && v !== null && !Array.isArray(v);
var strings = (v) => Array.isArray(v) && v.every((s) => typeof s === "string");
var each = (v, ok) => Array.isArray(v) && v.every((item) => isObj2(item) && ok(item));
function readSnapshot(text) {
  let value;
  try {
    value = JSON.parse(text);
  } catch {
    throw new Error("it is not JSON");
  }
  if (!isObj2(value)) throw new Error("it is not a snapshot");
  if (value.snapshot !== SNAPSHOT_FORMAT) {
    throw new Error(`its snapshot format is ${JSON.stringify(value.snapshot)}, and this codeplan reads ${SNAPSHOT_FORMAT}`);
  }
  if (value.rules !== RULES) {
    throw new Error(`its counting rules are ${JSON.stringify(value.rules)}, and this codeplan counts with ${RULES}`);
  }
  const shaped = isObj2(value.config) && (value.plan === "ok" || value.plan === "missing" || value.plan === "invalid") && typeof value.complete === "boolean" && typeof value.page === "string" && each(
    value.parts,
    (p) => typeof p.id === "string" && strings(p.paths) && typeof p.planned === "boolean" && typeof p.files === "number" && typeof p.lines === "number"
  ) && each(value.owned, (o) => typeof o.path === "string" && typeof o.part === "string" && typeof o.pattern === "string") && strings(value.orphans) && each(value.contested, (c) => typeof c.path === "string" && strings(c.parts)) && strings(value.unwatched) && each(value.diagnostics, (d) => typeof d.at === "string" && typeof d.code === "string");
  if (!shaped) throw new Error("it does not have the fields of a snapshot");
  return value;
}
function list(items) {
  const shown2 = items.slice(0, LIST_LIMIT).map((item) => `- ${item}`);
  if (items.length > LIST_LIMIT) shown2.push(`- and ${items.length - LIST_LIMIT} more`);
  return shown2.join("\n");
}
function statusLine(head) {
  const errors = head.diagnostics.filter((d) => d.severity === "error").length;
  const counted = [
    [errors, "errors"],
    [head.diagnostics.length - errors, "warnings"],
    [head.orphans.length, "orphan files"],
    [head.contested.length, "contested files"],
    [head.unwatched.length, "unwatched files"]
  ];
  const reasons = counted.filter(([n]) => n > 0).map(([n, what]) => `${n} ${what}`);
  if (head.page === "outdated") reasons.push("page outdated");
  if (head.page === "missing") reasons.push("page missing");
  if (head.page === "foreign") reasons.push("foreign page");
  if (!head.complete) reasons.push("measurement incomplete");
  return reasons.length === 0 ? "check: ok" : `check: fails (${reasons.join(", ")})`;
}
var sameJson = (a, b) => JSON.stringify(a) === JSON.stringify(b);
function changes(head, base) {
  if (base.plan === "missing") {
    return { blocks: ["No plan in the base, so there is nothing to compare."], rulesDiffer: false };
  }
  if (!base.complete || !head.complete) {
    const side = base.complete ? "head" : "base";
    return { blocks: [`Measurement is incomplete in the ${side}, so changes are not shown.`], rulesDiffer: false };
  }
  const before = new Map(base.parts.map((p) => [p.id, p]));
  const after = new Map(head.parts.map((p) => [p.id, p]));
  const rows = [];
  for (const p of head.parts) {
    const b = before.get(p.id);
    if (b === void 0) {
      rows.push(`| ${p.id} | ${p.files} | ${p.lines} | new |`);
    } else if (b.planned && !p.planned || b.files !== p.files || b.lines !== p.lines) {
      const change = b.planned && p.files >= 1 ? "planned, has code" : "changed";
      rows.push(`| ${p.id} | ${b.files} \u2192 ${p.files} | ${b.lines} \u2192 ${p.lines} | ${change} |`);
    }
  }
  for (const b of base.parts) {
    if (!after.has(b.id)) rows.push(`| ${b.id} | ${b.files} | ${b.lines} | removed |`);
  }
  const blocks = rows.length === 0 ? ["No part changed."] : [["| Part | Files | Lines | Change |", "|---|---|---|---|", ...rows].join("\n")];
  const rulesDiffer = !sameJson(base.config, head.config) || head.parts.some((p) => before.has(p.id) && !sameJson(before.get(p.id).paths, p.paths));
  if (rulesDiffer) {
    blocks.push("The config or part patterns changed, so some changes come from new rules, not new code.");
  }
  return { blocks, rulesDiffer };
}
function newDirectories(head, base) {
  const dirOf2 = (path) => path.slice(0, Math.max(0, path.lastIndexOf("/")));
  const baseDirs = new Set(
    [...base.owned.map((o) => o.path), ...base.orphans, ...base.contested.map((c) => c.path)].map(dirOf2)
  );
  const baseParts = new Set(base.parts.map((p) => p.id));
  const groups = /* @__PURE__ */ new Map();
  for (const file of head.owned) {
    if (!file.path.includes("/")) continue;
    const dir = dirOf2(file.path);
    const fixed = fixedHead(file.pattern);
    if (baseDirs.has(dir) || !baseParts.has(file.part)) continue;
    if (!`${dir}/`.startsWith(fixed) || dir.length + 1 <= fixed.length) continue;
    const key = JSON.stringify([dir, file.part, file.pattern]);
    const group = groups.get(key) ?? { dir, part: file.part, pattern: file.pattern, files: 0 };
    group.files++;
    groups.set(key, group);
  }
  return [...groups.values()].sort((a, b) => byCodeUnit(a.dir, b.dir) || byCodeUnit(a.part, b.part) || byCodeUnit(a.pattern, b.pattern)).map((g) => `${plain(g.dir)}/: ${g.files} files went to part ${g.part} through ${plain(g.pattern)}`);
}
function reportText(head, base) {
  const blocks = ["## codeplan", statusLine(head)];
  if (head.diagnostics.length > 0) {
    blocks.push("### Findings");
    blocks.push(list(head.diagnostics.map((d) => `${plain(d.at)}: ${d.code} [${d.severity}] ${plain(d.message)}`)));
  }
  if (!head.complete) {
    blocks.push("Measurement is incomplete, so files in no part are not listed.");
  } else if (head.orphans.length > 0) {
    blocks.push("### Files in no part", list(head.orphans.map(plain)));
  }
  if (head.contested.length > 0) {
    blocks.push("### Contested files", list(head.contested.map((c) => `${plain(c.path)}: ${c.parts.join(", ")}`)));
  }
  if (head.unwatched.length > 0) {
    blocks.push("### Unwatched files", list(head.unwatched.map(plain)));
  }
  if (base !== null) {
    const changed = changes(head, base);
    blocks.push("### Changes", ...changed.blocks);
    if (!changed.rulesDiffer && base.complete && head.complete) {
      const dirs = newDirectories(head, base);
      if (dirs.length > 0) blocks.push("### New directories", list(dirs));
    }
  }
  return `${blocks.join("\n\n")}
`;
}

// src/layout.ts
var GAP = 8;
var LABEL_HEIGHT = 3 * 16 + 8;
var TITLE_HEIGHT = 20;
var LABEL_CAP = 240;
var RATIO = 3;
function textWidth(text) {
  let width = 0;
  for (const ch of text) width += ch.codePointAt(0) < 4352 ? 7 : 13;
  return width;
}
function labelLines(p) {
  return [p.name === "" ? p.id : p.name, `${p.lines} lines`, p.planned ? "planned" : `${p.files} files`];
}
function labelWidth(p) {
  return Math.min(LABEL_CAP, Math.max(...labelLines(p).map(textWidth)) + 12);
}
function titleWidth(zone) {
  return Math.min(LABEL_CAP, textWidth(zone) + 12);
}
function partSize(a, lines) {
  const b = LABEL_HEIGHT;
  const t2 = 16e4 * lines;
  if ((a * b) ** 2 >= t2) return { w: a, h: b };
  const w = Math.max(a, Math.ceil(Math.sqrt(Math.sqrt(t2))));
  let h = Math.max(b, Math.floor(Math.sqrt(t2) / w));
  while ((w * h) ** 2 < t2) h++;
  if ((w * h) ** 2 > 25e4 * lines) throw new Error(`no part size for ${a} and ${lines} lines`);
  return { w, h };
}
function packRows(blocks, width) {
  const rows = [];
  for (const block of blocks) {
    const row = rows.at(-1);
    if (row && row.w + GAP + block.w <= width) {
      row.blocks.push(block);
      row.w += GAP + block.w;
      row.h = Math.max(row.h, block.h);
    } else {
      rows.push({ blocks: [block], w: block.w, h: block.h });
    }
  }
  const w = Math.max(0, ...rows.map((r) => r.w));
  const h = rows.reduce((sum, r, i) => sum + r.h + (i > 0 ? GAP : 0), 0);
  return {
    w,
    h,
    place(x, y) {
      let top = y;
      for (const row of rows) {
        let left = x;
        for (const block of row.blocks) {
          block.place(left, top);
          left += block.w + GAP;
        }
        top += row.h + GAP;
      }
    }
  };
}
function rowWidth(blocks) {
  const area = blocks.reduce((sum, b) => sum + (b.w + GAP) * (b.h + GAP), 0);
  return Math.max(Math.ceil(Math.sqrt(area)), ...blocks.map((b) => b.w));
}
var EXTERNAL_HEIGHT = 2 * 16 + 8;
var MARGIN = 8;
function externalWidth(e) {
  return Math.min(LABEL_CAP, Math.max(textWidth(e.name === "" ? e.id : e.name), textWidth("external")) + 12);
}
function layOut(inputs, externalInputs = [], relations = { edges: [], connectors: [] }) {
  if (inputs.length === 0 && externalInputs.length === 0) {
    return { width: 1, height: 1, zones: [], parts: [], externals: [], edges: [], connectors: [] };
  }
  const parts = inputs.map((p) => {
    const a = labelWidth(p);
    const { w, h } = partSize(a, p.lines);
    return { id: p.id, x: 0, y: 0, w, h, label: { x: 0, y: 0, w: a, h: LABEL_HEIGHT } };
  });
  const partBlock = (part) => ({
    w: part.w,
    h: part.h,
    place(x, y) {
      part.x = x;
      part.y = y;
      part.label.x = x;
      part.label.y = y;
    }
  });
  const zoneNames = [...new Set(inputs.map((p) => p.zone ?? "").filter((z) => z !== ""))].sort(byCodeUnit);
  const zones = [];
  const blocks = [];
  for (const name of zoneNames) {
    const members = parts.filter((_, i) => inputs[i].zone === name).map(partBlock);
    const inner = packRows(members, rowWidth(members));
    const tw = titleWidth(name);
    const zone = {
      name,
      x: 0,
      y: 0,
      // Side margins GAP; parts start below the title box and a GAP.
      w: Math.max(inner.w, tw) + 2 * GAP,
      h: TITLE_HEIGHT + GAP + inner.h + GAP,
      title: { x: 0, y: 0, w: tw, h: TITLE_HEIGHT }
    };
    zones.push(zone);
    blocks.push({
      w: zone.w,
      h: zone.h,
      place(x, y) {
        zone.x = x;
        zone.y = y;
        zone.title.x = x + GAP;
        zone.title.y = y;
        inner.place(x + GAP, y + TITLE_HEIGHT + GAP);
      }
    });
  }
  parts.forEach((part, i) => {
    const zone = inputs[i].zone ?? "";
    if (zone === "") blocks.push(partBlock(part));
  });
  const all = packRows(blocks, blocks.length > 0 ? rowWidth(blocks) : 0);
  all.place(MARGIN, MARGIN);
  const externals = externalInputs.map((e) => {
    const w = externalWidth(e);
    return { id: e.id, x: 0, y: 0, w, h: EXTERNAL_HEIGHT, label: { x: 0, y: 0, w, h: EXTERNAL_HEIGHT } };
  });
  let contentW = all.w;
  let contentH = all.h;
  if (externals.length > 0) {
    const extBlocks = externals.map(partBlock);
    const rows = packRows(extBlocks, Math.max(all.w, rowWidth(extBlocks)));
    const top = blocks.length > 0 ? all.h + GAP : 0;
    rows.place(MARGIN, MARGIN + top);
    contentW = Math.max(all.w, rows.w);
    contentH = top + rows.h;
  }
  let width = contentW + 2 * MARGIN;
  let height = contentH + 2 * MARGIN;
  if (width > RATIO * height) height = Math.ceil(width / RATIO);
  if (height > RATIO * width) width = Math.ceil(height / RATIO);
  const placeOf = new Map([...parts, ...externals].map((r) => [r.id, r]));
  let router = null;
  const routes = /* @__PURE__ */ new Map();
  const route = (from, to) => {
    const key = JSON.stringify([from, to]);
    let points = routes.get(key);
    if (!points) {
      router ??= makeRouter([...parts, ...externals]);
      points = router(placeOf.get(from), placeOf.get(to));
      routes.set(key, points);
    }
    return points.map((p) => [p[0], p[1]]);
  };
  const edges = relations.edges.map((e) => ({ from: e.from, to: e.to, points: route(e.from, e.to) }));
  const connectors = relations.connectors.map((c) => ({
    scenario: c.scenario,
    step: c.step,
    points: route(c.from, c.to)
  }));
  return { width, height, zones, parts, externals, edges, connectors };
}
var PAD = 4;
var BEND = 24;
function makeRouter(rects) {
  const padded = rects.map((r) => ({ l: r.x - PAD, r: r.x + r.w + PAD, t: r.y - PAD, b: r.y + r.h + PAD }));
  const xs = [...new Set(padded.flatMap((p) => [p.l, p.r]))].sort((a, b) => a - b);
  const ys = [...new Set(padded.flatMap((p) => [p.t, p.b]))].sort((a, b) => a - b);
  const nx = xs.length;
  const ny = ys.length;
  const xi = new Map(xs.map((x, i) => [x, i]));
  const yi = new Map(ys.map((y, j) => [y, j]));
  const blockedH = new Uint8Array(nx * ny);
  const blockedV = new Uint8Array(nx * ny);
  for (const p of padded) {
    const [l, r, t, b] = [xi.get(p.l), xi.get(p.r), yi.get(p.t), yi.get(p.b)];
    for (let j = t + 1; j < b; j++) for (let i = l; i < r; i++) blockedH[j * nx + i] = 1;
    for (let j = t; j < b; j++) for (let i = l + 1; i < r; i++) blockedV[j * nx + i] = 1;
  }
  const states = nx * ny * 4;
  const dist = new Float64Array(states);
  const prev = new Int32Array(states);
  const seen = new Int32Array(states);
  let search = 0;
  let heapF = new Float64Array(1024);
  let heapS = new Int32Array(1024);
  let size = 0;
  const push = (f, s) => {
    if (size === heapF.length) {
      const f2 = new Float64Array(size * 2);
      f2.set(heapF);
      heapF = f2;
      const s2 = new Int32Array(size * 2);
      s2.set(heapS);
      heapS = s2;
    }
    let i = size++;
    while (i > 0) {
      const parent = i - 1 >> 1;
      if (heapF[parent] < f || heapF[parent] === f && heapS[parent] <= s) break;
      heapF[i] = heapF[parent];
      heapS[i] = heapS[parent];
      i = parent;
    }
    heapF[i] = f;
    heapS[i] = s;
  };
  const pop = () => {
    const top = heapS[0];
    size--;
    if (size > 0) {
      const f = heapF[size];
      const s = heapS[size];
      let i = 0;
      for (; ; ) {
        const l = 2 * i + 1;
        if (l >= size) break;
        const r = l + 1;
        const c = r < size && (heapF[r] < heapF[l] || heapF[r] === heapF[l] && heapS[r] < heapS[l]) ? r : l;
        if (heapF[c] > f || heapF[c] === f && heapS[c] >= s) break;
        heapF[i] = heapF[c];
        heapS[i] = heapS[c];
        i = c;
      }
      heapF[i] = f;
      heapS[i] = s;
    }
    return top;
  };
  const step = [
    [1, 0],
    [0, 1],
    [-1, 0],
    [0, -1]
  ];
  const ports = (r) => {
    const p = { l: r.x - PAD, r: r.x + r.w + PAD, t: r.y - PAD, b: r.y + r.h + PAD };
    const cx = r.x + Math.floor(r.w / 2);
    const cy = r.y + Math.floor(r.h / 2);
    return [
      { side: [r.x + r.w, cy], out: [p.r, cy], corner: [p.r, p.t], dir: 3 },
      { side: [r.x + r.w, cy], out: [p.r, cy], corner: [p.r, p.b], dir: 1 },
      { side: [cx, r.y + r.h], out: [cx, p.b], corner: [p.r, p.b], dir: 0 },
      { side: [cx, r.y + r.h], out: [cx, p.b], corner: [p.l, p.b], dir: 2 },
      { side: [r.x, cy], out: [p.l, cy], corner: [p.l, p.b], dir: 1 },
      { side: [r.x, cy], out: [p.l, cy], corner: [p.l, p.t], dir: 3 },
      { side: [cx, r.y], out: [cx, p.t], corner: [p.l, p.t], dir: 2 },
      { side: [cx, r.y], out: [cx, p.t], corner: [p.r, p.t], dir: 0 }
    ];
  };
  const along = (q) => PAD + Math.abs(q.corner[0] - q.out[0]) + Math.abs(q.corner[1] - q.out[1]);
  const nodeOf = (p) => yi.get(p[1]) * nx + xi.get(p[0]);
  return (a, b) => {
    search++;
    size = 0;
    const goalBox = { l: b.x - PAD, r: b.x + b.w + PAD, t: b.y - PAD, b: b.y + b.h + PAD };
    const h = (n) => {
      const x = xs[n % nx];
      const y = ys[Math.floor(n / nx)];
      return Math.max(0, goalBox.l - x, x - goalBox.r) + Math.max(0, goalBox.t - y, y - goalBox.b);
    };
    const visit = (s2, g, from) => {
      if (seen[s2] === search && dist[s2] <= g) return;
      seen[s2] = search;
      dist[s2] = g;
      prev[s2] = from;
      push(g + h(s2 >> 2), s2);
    };
    const starts = ports(a);
    starts.forEach((q, k) => visit(nodeOf(q.corner) * 4 + q.dir, along(q), -2 - k));
    const finishes = /* @__PURE__ */ new Map();
    for (const q of ports(b)) {
      const n = nodeOf(q.corner);
      const cost = along(q) + BEND;
      if (!finishes.has(n) || finishes.get(n).cost > cost) finishes.set(n, { cost, port: q });
    }
    let best = Infinity;
    let bestState = -1;
    while (size > 0) {
      const top = heapF[0];
      if (top >= best) break;
      const s2 = pop();
      const g = dist[s2];
      if (g + h(s2 >> 2) > top) continue;
      const n = s2 >> 2;
      const finish2 = finishes.get(n);
      if (finish2 && g + finish2.cost < best) {
        best = g + finish2.cost;
        bestState = s2;
      }
      const dir = s2 & 3;
      const i = n % nx;
      const j = (n - i) / nx;
      for (let nd = 0; nd < 4; nd++) {
        if (nd === (dir + 2) % 4) continue;
        const [dx, dy] = step[nd];
        const ni = i + dx;
        const nj = j + dy;
        if (ni < 0 || nj < 0 || ni >= nx || nj >= ny) continue;
        if (dx !== 0 ? blockedH[j * nx + Math.min(i, ni)] : blockedV[Math.min(j, nj) * nx + i]) continue;
        const len = Math.abs(xs[ni] - xs[i]) + Math.abs(ys[nj] - ys[j]);
        visit((nj * nx + ni) * 4 + nd, g + len + (nd === dir ? 0 : BEND), s2);
      }
    }
    if (bestState < 0) throw new Error("no route between two rectangles");
    const finish = finishes.get(bestState >> 2).port;
    const path = [finish.side, finish.out];
    let s = bestState;
    for (; ; ) {
      const n = s >> 2;
      const i = n % nx;
      path.push([xs[i], ys[(n - i) / nx]]);
      const p = prev[s];
      if (p <= -2) {
        const q = starts[-2 - p];
        path.push(q.out, q.side);
        break;
      }
      s = p;
    }
    path.reverse();
    const out = [];
    for (const p of path) {
      const last = out.at(-1);
      if (last && last[0] === p[0] && last[1] === p[1]) continue;
      if (out.length >= 2) {
        const q = out.at(-2);
        if (q[0] === last[0] && last[0] === p[0] || q[1] === last[1] && last[1] === p[1]) out.pop();
      }
      out.push(p);
    }
    return out;
  };
}

// src/page.ts
var PAGE_MARK = "<!doctype html><!-- codeplan page -->";
var BLOCK_OPEN = '<script type="application/json" id="codeplan-data">';
function blockJson(data) {
  return JSON.stringify(data).replace(
    /[<>&\u2028\u2029]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`
  );
}
var THEMES = {
  light: {
    "--cp-bg": "#fbfaf7",
    "--cp-text": "#1d2433",
    "--cp-text-dim": "#4f5869",
    "--cp-part-fill": "#ffffff",
    "--cp-part-line": "#5d6b82",
    "--cp-ext-fill": "#eef3f8",
    "--cp-ext-line": "#5f6f86",
    "--cp-zone-fill": "#efece4",
    "--cp-zone-line": "#857c66",
    "--cp-zone-title": "#4a4436",
    "--cp-edge": "#5b6170",
    "--cp-connector": "#8a6a1f",
    "--cp-active": "#b5400f",
    "--cp-control-fill": "#ffffff",
    "--cp-control-line": "#6b7486",
    "--cp-control-text": "#1d2433"
  },
  dark: {
    "--cp-bg": "#14171d",
    "--cp-text": "#e7e9ee",
    "--cp-text-dim": "#a9b0bd",
    "--cp-part-fill": "#1e232c",
    "--cp-part-line": "#8d9ab0",
    "--cp-ext-fill": "#1b2530",
    "--cp-ext-line": "#8fa3ba",
    "--cp-zone-fill": "#22211d",
    "--cp-zone-line": "#9a9079",
    "--cp-zone-title": "#d8cfb8",
    "--cp-edge": "#9aa1ae",
    "--cp-connector": "#d0a650",
    "--cp-active": "#f08a4b",
    "--cp-control-fill": "#1e232c",
    "--cp-control-line": "#8d96a6",
    "--cp-control-text": "#e7e9ee"
  }
};
var tokens = (theme) => Object.entries(theme).map(([k, v]) => `${k}: ${v};`).join(" ");
var STYLE = `
:root { color-scheme: light dark; ${tokens(THEMES.light)} }
@media (prefers-color-scheme: dark) { :root:not([data-theme]) { ${tokens(THEMES.dark)} } }
:root[data-theme="light"] { color-scheme: light; ${tokens(THEMES.light)} }
:root[data-theme="dark"] { color-scheme: dark; ${tokens(THEMES.dark)} }
html, body { margin: 0; background: var(--cp-bg); color: var(--cp-text); font: 13px/1.4 system-ui, sans-serif; }
main { padding: 16px; }
#tools { display: flex; justify-content: flex-end; margin: 0 0 8px; }
#plane { display: block; width: 100%; height: auto; max-height: 80vh; background: transparent; }
.zone { fill: var(--cp-zone-fill); stroke: var(--cp-zone-line); stroke-width: 1; }
.zone-title { fill: var(--cp-zone-title); font-size: 12px; font-weight: 600; }
.part { fill: var(--cp-part-fill); stroke: var(--cp-part-line); stroke-width: 1; }
.part[data-planned] { stroke-dasharray: 6 3; }
.external { fill: var(--cp-ext-fill); stroke: var(--cp-ext-line); stroke-width: 1; stroke-dasharray: 4 2; }
.label { fill: var(--cp-text); font-size: 12px; }
.label .name { font-weight: 600; }
.edge { fill: none; stroke: var(--cp-edge); stroke-width: 1.25; }
.connector { fill: none; stroke: var(--cp-connector); stroke-width: 1.25; stroke-dasharray: 5 4; }
.arrow { fill: var(--cp-edge); stroke: none; }
rect[data-active] { stroke: var(--cp-active); stroke-width: 3; }
polyline[data-active] { stroke: var(--cp-active); stroke-width: 3; }
button, select { background: var(--cp-control-fill); color: var(--cp-control-text); border: 1px solid var(--cp-control-line); border-radius: 4px; font: inherit; padding: 2px 8px; }
button:disabled { opacity: 0.5; }
#walk { margin: 8px 0 0; display: flex; flex-wrap: wrap; gap: 8px; align-items: center; color: var(--cp-text); }
#walk:empty { display: none; }
#legend { margin: 8px 0 0; color: var(--cp-text-dim); }
#legend p { margin: 0; }
`;
var EARLY = `try { var t = localStorage.getItem("codeplan-theme"); if (t === "light" || t === "dark") document.documentElement.setAttribute("data-theme", t); } catch (e) {}`;
var VIEWER = `
(function () {
  var NS = "http://www.w3.org/2000/svg";
  var data = JSON.parse(document.getElementById("codeplan-data").textContent);
  var plan = data.plan;
  var layout = data.layout;
  var plane = document.getElementById("plane");
  plane.setAttribute("viewBox", "0 0 " + layout.width + " " + layout.height);
  function make(name, attrs, parent) {
    var el = document.createElementNS(NS, name);
    for (var k in attrs) el.setAttribute(k, String(attrs[k]));
    parent.appendChild(el);
    return el;
  }
  function html(name, parent) {
    var el = document.createElement(name);
    parent.appendChild(el);
    return el;
  }
  function textBox(box, lines, cls, parent) {
    var clip = make("svg", { x: box.x, y: box.y, width: box.w, height: box.h, overflow: "hidden" }, parent);
    for (var i = 0; i < lines.length; i++) {
      var t = make("text", { x: 6, y: 16 * (i + 1), "class": i === 0 ? cls + " name" : cls }, clip);
      t.textContent = lines[i];
    }
  }
  function str(v) { return typeof v === "string" ? v : ""; }
  function points(list) {
    var out = [];
    for (var i = 0; i < list.length; i++) out.push(list[i][0] + "," + list[i][1]);
    return out.join(" ");
  }

  var names = Object.create(null);
  var planParts = plan.parts || [];
  var planExternals = plan.externals || [];
  var planned = Object.create(null);
  for (var i = 0; i < planParts.length; i++) {
    names[planParts[i].id] = str(planParts[i].name) || planParts[i].id;
    if (planParts[i].planned === true) planned[planParts[i].id] = true;
  }
  for (var e = 0; e < planExternals.length; e++) names[planExternals[e].id] = str(planExternals[e].name) || planExternals[e].id;
  var measured = Object.create(null);
  for (var j = 0; j < data.measure.parts.length; j++) measured[data.measure.parts[j].id] = data.measure.parts[j];

  var defs = make("defs", {}, plane);
  // The arrowhead is 6 drawing units whatever the line width or highlight,
  // so it stays in the gap of 8 before the rectangle and inside the canvas.
  var marker = make("marker", { id: "arrow", viewBox: "0 0 10 10", refX: 10, refY: 5, markerWidth: 6, markerHeight: 6, markerUnits: "userSpaceOnUse", orient: "auto" }, defs);
  make("path", { d: "M0,0 L10,5 L0,10 z", "class": "arrow" }, marker);

  for (var z = 0; z < layout.zones.length; z++) {
    var zone = layout.zones[z];
    make("rect", { x: zone.x, y: zone.y, width: zone.w, height: zone.h, "class": "zone" }, plane);
    textBox(zone.title, [zone.name], "zone-title", plane);
  }
  var rects = Object.create(null);
  for (var p = 0; p < layout.parts.length; p++) {
    var part = layout.parts[p];
    var m = measured[part.id] || { lines: 0, files: 0 };
    rects[part.id] = make("rect", { x: part.x, y: part.y, width: part.w, height: part.h, "class": "part", "data-id": part.id }, plane);
    if (planned[part.id]) rects[part.id].setAttribute("data-planned", "true");
    textBox(part.label, [names[part.id] || part.id, m.lines + " lines", planned[part.id] ? "planned" : m.files + " files"], "label", plane);
  }
  for (var x = 0; x < layout.externals.length; x++) {
    var ext = layout.externals[x];
    rects[ext.id] = make("rect", { x: ext.x, y: ext.y, width: ext.w, height: ext.h, "class": "external", "data-id": ext.id }, plane);
    textBox(ext.label, [names[ext.id] || ext.id, "external"], "label", plane);
  }
  var edges = [];
  for (var d = 0; d < layout.edges.length; d++) {
    var edge = layout.edges[d];
    edges.push({ from: edge.from, to: edge.to, el: make("polyline", { points: points(edge.points), "class": "edge", "marker-end": "url(#arrow)", "data-from": edge.from, "data-to": edge.to }, plane) });
  }
  var connectors = [];
  for (var c = 0; c < layout.connectors.length; c++) {
    var con = layout.connectors[c];
    connectors.push({ scenario: con.scenario, step: con.step, el: make("polyline", { points: points(con.points), "class": "connector", "data-scenario": con.scenario, "data-step": con.step }, plane) });
  }

  var legend = document.getElementById("legend");
  function line(text) { html("p", legend).textContent = text; }
  var title = str(plan.name);
  line(data.measure.files + " files, " + data.measure.lines + " lines" + (title ? " \\u00b7 " + title : ""));
  if (data.orphans.length > 0) line(data.orphans.length + " orphan files: no part owns them");
  if (data.unwatched > 0) line(data.unwatched + " unwatched files: code outside the watch set");
  if (title) document.title = title;

  // The theme button: auto, then light, then dark, then auto again.
  var root = document.documentElement;
  var themeButton = html("button", document.getElementById("tools"));
  themeButton.type = "button";
  themeButton.setAttribute("data-role", "theme");
  themeButton.title = "Colour theme: auto follows the system setting";
  function themeNow() {
    var t = root.getAttribute("data-theme");
    return t === "light" || t === "dark" ? t : "auto";
  }
  themeButton.textContent = themeNow();
  themeButton.addEventListener("click", function () {
    var next = { auto: "light", light: "dark", dark: "auto" }[themeNow()];
    if (next === "auto") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", next);
    themeButton.textContent = next;
    try {
      if (next === "auto") localStorage.removeItem("codeplan-theme");
      else localStorage.setItem("codeplan-theme", next);
    } catch (e) {}
  });

  var scenarios = plan.scenarios || [];
  if (scenarios.length === 0) return;
  var walk = document.getElementById("walk");
  var select = html("select", walk);
  select.setAttribute("aria-label", "Scenario");
  var none = html("option", select);
  none.value = "";
  none.textContent = "(none)";
  for (var s = 0; s < scenarios.length; s++) {
    var option = html("option", select);
    option.value = String(s);
    option.textContent = str(scenarios[s].name) || scenarios[s].id;
  }
  var prev = html("button", walk);
  prev.type = "button";
  prev.textContent = "Previous";
  prev.title = "Previous step (Left arrow key)";
  var next = html("button", walk);
  next.type = "button";
  next.textContent = "Next";
  next.title = "Next step (Right arrow key). Esc ends the walk-through.";
  var stepText = html("span", walk);
  stepText.setAttribute("data-role", "step");
  prev.disabled = true;
  next.disabled = true;

  var current = null;
  var REF = /\\{([a-z0-9](?:[a-z0-9-]*[a-z0-9])?)\\}/g;
  function fill(text) {
    return str(text).replace(REF, function (whole, id) { return id in names ? names[id] : whole; });
  }
  function setHash(text) {
    history.replaceState(null, "", text ? "#" + text : location.pathname + location.search);
  }
  function clearActive() {
    for (var id in rects) rects[id].removeAttribute("data-active");
    for (var i = 0; i < edges.length; i++) edges[i].el.removeAttribute("data-active");
    for (var k = 0; k < connectors.length; k++) connectors[k].el.removeAttribute("data-active");
  }
  // keep: opened from the fragment, which is then left as it is.
  function show(keep) {
    clearActive();
    var scenario = scenarios[current.index];
    var steps = scenario.steps || [];
    if (steps.length === 0) {
      stepText.textContent = "This scenario has no steps";
      prev.disabled = true;
      next.disabled = true;
      if (!keep) setHash("scenario=" + scenario.id);
      return;
    }
    var k = current.step;
    var at = steps[k].at;
    if (rects[at]) rects[at].setAttribute("data-active", "");
    if (k > 0 && steps[k - 1].at !== at) {
      var before = steps[k - 1].at;
      var lit = false;
      for (var i = 0; i < edges.length; i++) {
        if ((edges[i].from === before && edges[i].to === at) || (edges[i].from === at && edges[i].to === before)) {
          edges[i].el.setAttribute("data-active", "");
          lit = true;
        }
      }
      if (!lit) {
        for (var c = 0; c < connectors.length; c++) {
          if (connectors[c].scenario === scenario.id && connectors[c].step === k) connectors[c].el.setAttribute("data-active", "");
        }
      }
    }
    stepText.textContent = (k + 1) + "/" + steps.length + " " + (names[at] || at) + ": " + fill(steps[k].does);
    prev.disabled = k === 0;
    next.disabled = k === steps.length - 1;
    if (!keep) setHash("scenario=" + scenario.id + "&step=" + (k + 1));
  }
  function open(index, step, fromHash) {
    current = { index: index, step: step };
    select.value = String(index);
    show(fromHash);
    if (!next.disabled) next.focus();
  }
  function close() {
    current = null;
    clearActive();
    select.value = "";
    stepText.textContent = "";
    prev.disabled = true;
    next.disabled = true;
    setHash("");
  }
  function move(by) {
    if (current === null) return;
    var steps = scenarios[current.index].steps || [];
    var k = current.step + by;
    if (k < 0 || k >= steps.length) return;
    current.step = k;
    show();
  }
  select.addEventListener("change", function () {
    if (select.value === "") close();
    else open(Number(select.value), 0);
  });
  prev.addEventListener("click", function () { move(-1); });
  next.addEventListener("click", function () { move(1); });
  document.addEventListener("keydown", function (event) {
    if (current === null || document.activeElement === select) return;
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    if (event.key === "ArrowLeft") { event.preventDefault(); move(-1); }
    else if (event.key === "ArrowRight") { event.preventDefault(); move(1); }
    else if (event.key === "Escape") close();
  });

  // Opens what the fragment names: on load, and when the fragment changes
  // later (replaceState above does not fire hashchange). An unknown id is
  // ignored; no fragment at all ends the walk-through.
  function fromHash(onLoad) {
    var text = location.hash.replace(/^#/, "");
    if (text === "") {
      if (!onLoad && current !== null) close();
      return;
    }
    var wanted = Object.create(null);
    var pieces = text.split("&");
    for (var q = 0; q < pieces.length; q++) {
      var eq = pieces[q].indexOf("=");
      if (eq > 0) {
        try { wanted[decodeURIComponent(pieces[q].slice(0, eq))] = decodeURIComponent(pieces[q].slice(eq + 1)); } catch (err) {}
      }
    }
    for (var w = 0; w < scenarios.length; w++) {
      if (scenarios[w].id !== wanted.scenario) continue;
      var n = (scenarios[w].steps || []).length;
      var k = /^[0-9]+$/.test(wanted.step || "") ? parseInt(wanted.step, 10) : 0;
      open(w, k >= 1 && k <= n ? k - 1 : 0, true);
      return;
    }
  }
  window.addEventListener("hashchange", function () { fromHash(false); });
  fromHash(true);
})();
`;
var FRAME = {
  head: `${PAGE_MARK}
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>codeplan</title>
<style>${STYLE}</style>
<script>${EARLY}</script>
</head>
<body>
<main>
<div id="tools"></div>
<svg id="plane" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="parts of the repository"></svg>
<div id="walk"></div>
<div id="legend"></div>
</main>
`,
  tail: `
<script>${VIEWER}</script>
</body>
</html>
`
};
function pageHtml(data, frame = FRAME) {
  return frame.head + BLOCK_OPEN + blockJson(data) + "</script>" + frame.tail;
}

// src/cli.ts
var USAGE = `Usage: node codeplan.mjs <command> [--repo <dir>] [options]

Commands:
  check   Measure the repository against its plan and report files no part
          owns, files two parts own equally, code outside the watch set,
          problems in the plan, and a viewer page that update would change.
          Writes nothing.
  update  Write the viewer page (codeplan.html under home by default) from
          the plan and the code.
  draft   Read the repository and print a first plan.json and codeplan.json
          as one JSON document on standard output. Writes nothing.
  summary Print the plan as Markdown on standard output: the parts with
          their counts, uses, externals, scenarios and a mermaid diagram.
          Writes nothing and does not read the viewer page.
  snapshot Print what check finds as JSON on standard output: the config
          used, each part's counts, which pattern owns each file, the
          problem lists and the findings. Writes nothing.
  report  Print the state of the plan as Markdown for a pull request. With
          --base, also what changed since that snapshot. Writes nothing and
          always exits with 0 when it printed; check is what fails CI.
  help    Show this text.

Options:
  --repo <dir>        Repository to read (default: the current directory).

Summary options:
  --mermaid           Print only the mermaid diagram.

Report options:
  --base <file>       A snapshot of the tree to compare with, made by the
                      snapshot command.

Draft options:
  --max-parts <n>     Most parts to propose (default 16).
  --language <name>   Language rules to use: js or java. Repeat for both.
  --root <dir>        Source root to use instead of the language rules.
                      Repeat for more than one.

Exit codes for check: 0 no problems, 1 problems or warnings,
2 an error in the plan, the config or plan file cannot be read, or the page
place holds something that is not a codeplan page.
Exit codes for update: 0 written, already current, or no plan file;
1 contested files (nothing written); 2 as for check.
Exit codes for draft: 0 printed, 2 an option, the config or the repository
cannot be used.
Exit codes for summary: 0 printed (warnings, orphans, contested and
unwatched files do not change it); 2 an error in the plan, no plan file, or
the config or plan file cannot be read.
Exit codes for snapshot and report: 0 printed; 2 the config cannot be used,
the plan file is not JSON, or the --base file cannot be used.
`;
var LIST_LIMIT2 = 30;
var COMMANDS = ["check", "update", "draft", "summary", "snapshot", "report", "help"];
var OPTIONS = ["--repo", "--max-parts", "--language", "--root", "--mermaid", "--base"];
function pagePlace(repo, page) {
  const segments = page.split("/");
  for (let i = 1; i < segments.length; i++) {
    const dir = lstatSync3(join6(repo, ...segments.slice(0, i)), { throwIfNoEntry: false });
    if (dir === void 0) break;
    if (dir.isSymbolicLink()) return { kind: "other", what: "a path through a symbolic link" };
    if (!dir.isDirectory()) return { kind: "other", what: "a path that passes through a file" };
  }
  const stat = lstatSync3(join6(repo, page), { throwIfNoEntry: false });
  if (stat === void 0) return { kind: "absent" };
  if (stat.isSymbolicLink()) return { kind: "other", what: "a symbolic link" };
  if (!stat.isFile()) return { kind: "other", what: stat.isDirectory() ? "a directory" : "not a regular file" };
  const bytes = readFileSync5(join6(repo, page));
  if (!bytes.subarray(0, PAGE_MARK.length).equals(Buffer.from(PAGE_MARK))) {
    return { kind: "other", what: "a file that does not start with the codeplan page mark" };
  }
  return { kind: "page", bytes };
}
function check(repo, page = "compare") {
  let config;
  try {
    config = readConfig(repo);
  } catch (e) {
    if (e instanceof SettingsError) return { kind: "error", message: e.message };
    throw e;
  }
  const file = readPlanFile(repo, config.home);
  if (file.kind === "not-json") return { kind: "error", message: file.message };
  const place = page === "skip" ? { kind: "absent" } : pagePlace(repo, config.page);
  if (place.kind === "other" && page === "compare") {
    return {
      kind: "error",
      message: `${config.page} is ${place.what}, not a codeplan page; it was left as it is. Move it away, or set page in codeplan.json to another file under home`
    };
  }
  const notCompared = {
    path: config.page,
    bytes: null,
    current: false,
    state: place.kind === "other" ? "foreign" : "not-checked"
  };
  if (file.kind === "missing") {
    const plan = planPath(config.home);
    const measurement2 = measure(repo, config, []);
    const diagnostics2 = [diagnostic("no-plan", "plan", `${plan} not found, so every watched file is an orphan`)];
    return { kind: "measured", config, plan: null, measured: [], measurement: measurement2, diagnostics: diagnostics2, excluded: 0, page: notCompared };
  }
  const prepared = preparePlan(file.value);
  const measurement = measure(repo, config, prepared.parts);
  const diagnostics = diagnosePlan(prepared, {
    repo,
    files: new Map(measurement.parts.map((p) => [p.id, p.files])),
    owners: measurement.owners
  });
  const result2 = {
    kind: "measured",
    config,
    plan: file.value,
    measured: prepared.parts,
    measurement,
    diagnostics,
    excluded: prepared.excluded
  };
  if (page === "skip" || place.kind === "other" || diagnostics.some((d) => d.severity === "error") || measurement.contested.length > 0) {
    return { ...result2, page: notCompared };
  }
  const bytes = Buffer.from(pageFor(file.value, measurement), "utf8");
  const current = place.kind === "page" && place.bytes.equals(bytes);
  const state = place.kind === "absent" ? "missing" : current ? "ok" : "outdated";
  return { ...result2, page: { path: config.page, bytes, current, state } };
}
function pageFor(plan, m) {
  const raw = plan;
  const parts = m.parts.map((p) => ({ id: p.id, files: p.files, lines: p.lines }));
  const externals = raw.externals ?? [];
  const edges = [];
  const seen = /* @__PURE__ */ new Set();
  for (const part of raw.parts) {
    for (const use of part.uses ?? []) {
      const key = JSON.stringify([part.id, use.to]);
      if (use.to === part.id || seen.has(key)) continue;
      seen.add(key);
      edges.push({ from: part.id, to: use.to });
    }
  }
  const connectors = [];
  for (const scenario of raw.scenarios ?? []) {
    scenario.steps.forEach((step, k) => {
      if (k === 0) return;
      const a = scenario.steps[k - 1].at;
      const b = step.at;
      if (a === b || seen.has(JSON.stringify([a, b])) || seen.has(JSON.stringify([b, a]))) return;
      connectors.push({ scenario: scenario.id, step: k, from: a, to: b });
    });
  }
  const layout = layOut(
    raw.parts.map((part, i) => ({
      id: part.id,
      name: part.name,
      zone: part.zone,
      lines: parts[i].lines,
      files: parts[i].files,
      planned: part.planned === true
    })),
    externals.map((e) => ({ id: e.id, name: e.name })),
    { edges, connectors }
  );
  return pageHtml({
    format: 2,
    plan,
    measure: {
      parts,
      files: parts.reduce((s, p) => s + p.files, 0),
      lines: parts.reduce((s, p) => s + p.lines, 0)
    },
    orphans: m.orphans,
    unwatched: m.unwatched.length,
    layout
  });
}
function update(repo) {
  const result2 = check(repo);
  if (result2.kind === "error") return { code: 2, stdout: "", stderr: `codeplan: ${result2.message}
` };
  const { config, diagnostics, measurement: m, page } = result2;
  const plan = planPath(config.home);
  if (diagnostics.some((d) => d.code === "no-plan")) {
    return { code: 0, stdout: `update: ${plan} not found, so no page was written
`, stderr: "" };
  }
  const errors = diagnostics.filter((d) => d.severity === "error");
  if (errors.length > 0) {
    let stderr = "";
    for (const d of errors) stderr += `${d.at}: ${d.code} [${d.severity}] ${d.message}
`;
    stderr += `update: the plan has errors, so ${page.path} was not written
  fix: correct ${plan}, then run update again
`;
    return { code: 2, stdout: "", stderr };
  }
  if (m.contested.length > 0) {
    const stderr = listSection(
      "contested files: two or more parts own them with equally strong patterns",
      "give one part a pattern with a longer fixed prefix, or remove the overlap",
      m.contested.map((c) => `${c.path} (${c.parts.join(", ")})`)
    ) + `update: contested files, so ${page.path} was not written
`;
    return { code: 1, stdout: "", stderr };
  }
  if (page.current) return { code: 0, stdout: `update: ${page.path} is up to date
`, stderr: "" };
  const target = join6(repo, page.path);
  let temp = null;
  try {
    mkdirSync(dirname(target), { recursive: true });
    for (let n = 0; temp === null; n++) {
      const name = join6(dirname(target), `.${basename(target)}.${process.pid}.${n}.tmp`);
      try {
        const fd = openSync(name, "wx");
        temp = name;
        try {
          writeSync(fd, page.bytes);
        } finally {
          closeSync(fd);
        }
      } catch (e) {
        if (e.code !== "EEXIST" || temp !== null) throw e;
      }
    }
    renameSync(temp, target);
  } catch (e) {
    if (temp !== null) rmSync(temp, { force: true });
    return { code: 2, stdout: "", stderr: `codeplan: cannot write ${page.path}: ${e.message}
` };
  }
  return { code: 0, stdout: `update: wrote ${page.path}
`, stderr: "" };
}
var FIXES = {
  "bad-shape": "give the field at that place the type and form of the plan format, or remove an unknown key",
  "repeated-id": "give each part, external and scenario an id of its own",
  "no-paths": "give the part at least one pattern in paths",
  "unknown-target": "name an existing part or external, or add it",
  "empty-part": "correct the part's paths so they match files, or remove the part",
  "planned-has-files": "remove planned from the part now that it has code; if the files are not its code, correct the part's paths",
  "start-missing": "point start at a file in the repository",
  "start-elsewhere": "point start at a file this part measures",
  "self-use": "remove the use of the part itself",
  "repeated-use": "keep one use for each target",
  "unused-external": "name the external in a use or a step, or remove it",
  "short-scenario": "give the scenario two steps or more",
  "gap-in-scenario": "add a use between the two steps' parts, or a step between them",
  "unknown-reference": "write the id of an existing part or external in the braces"
};
var fixFor = (code, plan) => code === "no-plan" ? `create ${plan} and give each part the paths it owns` : FIXES[code];
function findingLines(diagnostics, plan) {
  let text = "";
  for (const d of diagnostics) text += `${d.at}: ${d.code} [${d.severity}] ${d.message}
`;
  for (const code of new Set(diagnostics.map((d) => d.code))) text += `  fix: ${code}: ${fixFor(code, plan)}
`;
  return text;
}
function snapshotOf(repo) {
  const result2 = check(repo, "note");
  if (result2.kind === "error") return { code: 2, stdout: "", stderr: `codeplan: ${result2.message}
` };
  const plan = planPath(result2.config.home);
  return takeSnapshot({
    config: result2.config,
    hasPlan: result2.plan !== null,
    measured: result2.measured,
    measurement: result2.measurement,
    diagnostics: result2.diagnostics,
    excluded: result2.excluded,
    pageState: result2.page.state,
    fix: (code) => fixFor(code, plan)
  });
}
function snapshot(repo) {
  const taken = snapshotOf(repo);
  return "snapshot" in taken ? { code: 0, stdout: snapshotText(taken), stderr: "" } : taken;
}
function report(repo, baseFile) {
  let base = null;
  if (baseFile !== void 0) {
    try {
      let text;
      try {
        text = readFileSync5(baseFile, "utf8");
      } catch {
        throw new Error("it cannot be read");
      }
      base = readSnapshot(text);
    } catch (e) {
      return { code: 2, stdout: "", stderr: `codeplan: the --base file ${baseFile} cannot be used: ${e.message}
` };
    }
  }
  const head = snapshotOf(repo);
  return "snapshot" in head ? { code: 0, stdout: reportText(head, base), stderr: "" } : head;
}
function summary(repo, mermaid) {
  const result2 = check(repo, "skip");
  if (result2.kind === "error") return { code: 2, stdout: "", stderr: `codeplan: ${result2.message}
` };
  const stderr = findingLines(result2.diagnostics, planPath(result2.config.home));
  if (result2.plan === null || result2.diagnostics.some((d) => d.severity === "error")) {
    return { code: 2, stdout: "", stderr };
  }
  const stdout = mermaid ? mermaidText(result2.plan) : summaryText(result2.plan, result2.measurement);
  return { code: 0, stdout, stderr };
}
function listSection(title, fix, items) {
  if (items.length === 0) return "";
  const shown2 = items.slice(0, LIST_LIMIT2).map((i) => `  ${i}
`);
  const rest = items.length - LIST_LIMIT2;
  const more = rest > 0 ? `  ... and ${rest} more
` : "";
  return `${title} (${items.length})
  fix: ${fix}
${shown2.join("")}${more}`;
}
function checkReport(result2) {
  const { config, measurement: m, diagnostics, excluded } = result2;
  const plan = planPath(config.home);
  let stdout = `languages: ${config.languages.join(", ")}
`;
  stdout += `watched files: ${m.watchedFiles}
`;
  for (const p of m.parts) stdout += `part ${p.id}: ${p.files} files, ${p.lines} lines
`;
  let stderr = "";
  const orphans = excluded > 0 ? [] : m.orphans;
  if (excluded > 0) {
    stderr += `measurement incomplete: ${excluded} parts are not measured, so orphan files are not listed
  fix: correct the errors in ${plan} below
`;
  }
  stderr += listSection(
    "orphan files: no part owns them",
    `add a pattern to a part's paths in ${plan}, or list them under skip in codeplan.json`,
    orphans
  );
  stderr += listSection(
    "contested files: two or more parts own them with equally strong patterns",
    "give one part a pattern with a longer fixed prefix, or remove the overlap",
    m.contested.map((c) => `${c.path} (${c.parts.join(", ")})`)
  );
  stderr += listSection(
    "unwatched files: code outside the watch set",
    "add a pattern for them under watch, or under skip, in codeplan.json",
    m.unwatched
  );
  const outdated = result2.page.bytes !== null && !result2.page.current;
  if (outdated) {
    stderr += `page outdated: ${result2.page.path} differs from the page update would write now
  fix: run update
`;
  }
  stderr += findingLines(diagnostics, plan);
  const errors = diagnostics.filter((d) => d.severity === "error").length;
  const warnings = diagnostics.length - errors;
  const problems = orphans.length + m.contested.length + m.unwatched.length;
  if (problems === 0 && diagnostics.length === 0 && excluded === 0 && !outdated) {
    stdout += "check: ok\n";
    return { code: 0, stdout, stderr };
  }
  stdout += `check: ${excluded > 0 ? "measurement incomplete" : `${orphans.length} orphan`}, ${m.contested.length} contested, ${m.unwatched.length} unwatched, ${errors} errors, ${warnings} warnings${outdated ? ", page outdated" : ""}
`;
  return { code: errors > 0 ? 2 : 1, stdout, stderr };
}
function run(argv, cwd) {
  const usageError = (message) => ({
    code: 2,
    stdout: "",
    stderr: `codeplan: ${message}

${USAGE}`
  });
  let command;
  let repo = cwd;
  let maxParts;
  const languages = [];
  const roots = [];
  const draftFlags = [];
  let mermaid = false;
  let base;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--repo") {
      const value = argv[++i];
      if (value === void 0) return usageError("--repo needs a directory");
      repo = resolve(cwd, value);
    } else if (arg === "--mermaid") {
      mermaid = true;
    } else if (arg === "--base") {
      const value = argv[++i];
      if (value === void 0) return usageError("--base needs a file");
      base = resolve(cwd, value);
    } else if (OPTIONS.includes(arg)) {
      const value = argv[++i];
      if (value === void 0) return usageError(`${arg} needs a value`);
      draftFlags.push(arg);
      if (arg === "--max-parts") maxParts = value;
      else if (arg === "--language") languages.push(value);
      else roots.push(value);
    } else if (arg.startsWith("-")) {
      return usageError(`unknown option ${arg}`);
    } else if (command === void 0) {
      command = arg;
    } else {
      return usageError(`unexpected argument ${arg}`);
    }
  }
  if (command === "help") return { code: 0, stdout: USAGE, stderr: "" };
  if (command === void 0 || !COMMANDS.includes(command)) {
    return usageError(command === void 0 ? "no command given" : `unknown command ${command}`);
  }
  if (command !== "draft" && draftFlags.length > 0) {
    return usageError(`${draftFlags[0]} is an option of draft only`);
  }
  if (command !== "summary" && mermaid) return usageError("--mermaid is an option of summary only");
  if (command !== "report" && base !== void 0) return usageError("--base is an option of report only");
  if (maxParts !== void 0 && !/^[0-9]+$/.test(maxParts)) {
    return usageError(`--max-parts needs a whole number of 1 or more, not "${maxParts}"`);
  }
  const parts = maxParts === void 0 ? 16 : Number(maxParts);
  if (parts < 1) return usageError(`--max-parts needs a whole number of 1 or more, not "${maxParts}"`);
  if (!statSync(repo, { throwIfNoEntry: false })?.isDirectory()) {
    return { code: 2, stdout: "", stderr: `codeplan: repository not found: ${repo}
` };
  }
  if (command === "draft") {
    try {
      const out = draft(repo, {
        maxParts: parts,
        languages: languages.length > 0 ? languages : null,
        roots: roots.length > 0 ? roots : null
      });
      return { code: 0, stdout: `${JSON.stringify(out, null, 2)}
`, stderr: "" };
    } catch (e) {
      if (e instanceof SettingsError || e instanceof DraftError) {
        return { code: 2, stdout: "", stderr: `codeplan: ${e.message}
` };
      }
      throw e;
    }
  }
  if (command === "update") return update(repo);
  if (command === "summary") return summary(repo, mermaid);
  if (command === "snapshot") return snapshot(repo);
  if (command === "report") return report(repo, base);
  const result2 = check(repo);
  if (result2.kind === "error") {
    return { code: 2, stdout: "", stderr: `codeplan: ${result2.message}
` };
  }
  return checkReport(result2);
}

// src/bin.ts
var result = run(process.argv.slice(2), process.cwd());
process.stdout.write(result.stdout);
process.stderr.write(result.stderr);
process.exitCode = result.code;
