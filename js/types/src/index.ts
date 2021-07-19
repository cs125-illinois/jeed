import { Array, Boolean, Dictionary, Literal, Number, Partial, Record, Static, String, Union } from "runtypes"

export const Task = Union(
  Literal("template"),
  Literal("snippet"),
  Literal("compile"),
  Literal("kompile"),
  Literal("checkstyle"),
  Literal("ktlint"),
  Literal("complexity"),
  Literal("execute"),
  Literal("cexecute"),
  Literal("features"),
  Literal("mutations")
)
export type Task = Static<typeof Task>

export const FlatSource = Record({
  path: String,
  contents: String,
})
export type FlatSource = Static<typeof FlatSource>

export const Permission = Record({
  klass: String,
  name: String,
}).And(
  Partial({
    actions: String,
  })
)
export type Permission = Static<typeof Permission>

export const FileType = Union(Literal("JAVA"), Literal("KOTLIN"))
export type FileType = Static<typeof FileType>

export const Location = Record({ line: Number, column: Number })
export type Location = Static<typeof Location>

export const SourceRange = Record({
  start: Location,
  end: Location,
}).And(
  Partial({
    source: String,
  })
)
export type SourceRange = Static<typeof SourceRange>

export const SourceLocation = Record({
  source: String,
  line: Number,
  column: Number,
})
export type SourceLocation = Static<typeof SourceLocation>

export const Interval = Record({
  start: String.withConstraint((s) => !isNaN(Date.parse(s))),
  end: String.withConstraint((s) => !isNaN(Date.parse(s))),
})
export type Interval = Static<typeof Interval>

export const ServerStatus = Record({
  tasks: Array(Task),
  started: String.withConstraint((s) => !isNaN(Date.parse(s))),
  hostname: String,
  versions: Record({
    jeed: String,
    server: String,
    compiler: String,
    kompiler: String,
  }),
  counts: Record({
    submitted: Number,
    completed: Number,
    saved: Number,
  }),
  cache: Record({
    inUse: Boolean,
    sizeInMB: Number,
    hits: Number,
    misses: Number,
    hitRate: Number,
    evictionCount: Number,
    averageLoadPenalty: Number,
  }),
}).And(
  Partial({
    lastRequest: String.withConstraint((s) => !isNaN(Date.parse(s))),
  })
)
export type ServerStatus = Static<typeof ServerStatus>

export const SnippetArguments = Partial({
  indent: Number,
  fileType: FileType,
})
export type SnippetArguments = Static<typeof SnippetArguments>

export const CompilationArguments = Partial({
  wError: Boolean,
  XLint: String,
  enablePreview: Boolean,
  useCache: Boolean,
  waitForCache: Boolean,
})
export type CompilationArguments = Static<typeof CompilationArguments>

export const KompilationArguments = Partial({
  verbose: Boolean,
  allWarningsAsErrors: Boolean,
  useCache: Boolean,
  waitForCache: Boolean,
})
export type KompilationArguments = Static<typeof KompilationArguments>

export const CheckstyleArguments = Partial({
  sources: Array(String),
  failOnError: Boolean,
})
export type CheckstyleArguments = Static<typeof CheckstyleArguments>

export const KtLintArguments = Partial({
  sources: Array(String),
  failOnError: Boolean,
})
export type KtLintArguments = Static<typeof KtLintArguments>

export const ClassLoaderConfiguration = Partial({
  whitelistedClasses: Array(String),
  blacklistedClasses: Array(String),
  unsafeExceptions: Array(String),
  isolatedClasses: Array(String),
})
export type ClassLoaderConfiguration = Static<typeof ClassLoaderConfiguration>

export const SourceExecutionArguments = Partial({
  klass: String,
  method: String,
  timeout: Number,
  permissions: Array(Permission),
  maxExtraThreads: Number,
  maxOutputLines: Number,
  classLoaderConfiguration: ClassLoaderConfiguration,
  dryRun: Boolean,
  waitForShutdown: Boolean,
})
export type SourceExecutionArguments = Static<typeof SourceExecutionArguments>

export const ContainerExecutionArguments = Partial({
  klass: String,
  method: String,
  image: String,
  timeout: Number,
  maxOutputLines: Number,
  containerArguments: String,
})
export type ContainerExecutionArguments = Static<typeof ContainerExecutionArguments>

export const MutationsArguments = Partial({
  limit: Number,
  suppressWithComments: Boolean,
})
export type MutationsArguments = Static<typeof MutationsArguments>

export const TaskArguments = Partial({
  snippet: SnippetArguments,
  compilation: CompilationArguments,
  kompilation: KompilationArguments,
  checkstyle: CheckstyleArguments,
  ktlint: KtLintArguments,
  execution: SourceExecutionArguments,
  cexecution: ContainerExecutionArguments,
  mutations: MutationsArguments,
})
export type TaskArguments = Static<typeof TaskArguments>

export const Request = Record({
  tasks: Array(Task),
  label: String,
}).And(
  Partial({
    sources: Array(FlatSource),
    templates: Array(FlatSource),
    snippet: String,
    arguments: TaskArguments,
    checkForSnippet: Boolean,
  })
)
export type Request = Static<typeof Request>

export const Snippet = Record({
  sources: Dictionary(String),
  originalSource: String,
  rewrittenSource: String,
  snippetRange: SourceRange,
  wrappedClassName: String,
  looseCodeMethodName: String,
  fileType: FileType,
})
export type Snippet = Static<typeof Snippet>

export const TemplatedSourceResult = Record({
  sources: Dictionary(String),
  originalSources: Dictionary(String),
})
export type TemplatedSourceResult = Static<typeof TemplatedSourceResult>

export const CompilationMessage = Record({
  kind: String,
  message: String,
}).And(
  Partial({
    location: SourceLocation,
  })
)
export type CompilationMessage = Static<typeof CompilationMessage>

export const CompiledSourceResult = Record({
  messages: Array(CompilationMessage),
  compiled: String.withConstraint((s) => !isNaN(Date.parse(s))),
  interval: Interval,
  compilerName: String,
  cached: Boolean,
})
export type CompiledSourceResult = Static<typeof CompiledSourceResult>

export const CheckstyleError = Record({
  severity: String,
  location: SourceLocation,
  message: String,
})
export type CheckstyleError = Static<typeof CheckstyleError>

export const CheckstyleResults = Record({
  errors: Array(CheckstyleError),
})
export type CheckstyleResults = Static<typeof CheckstyleResults>

export const KtlintError = Record({
  ruleId: String,
  detail: String,
  location: SourceLocation,
})
export type KtlintError = Static<typeof KtlintError>

export const KtlintResults = Record({
  errors: Array(KtlintError),
})
export type KtlintResults = Static<typeof KtlintError>

export const FlatClassComplexity = Record({
  name: String,
  path: String,
  range: SourceRange,
  complexity: Number,
})
export type FlatClassComplexity = Static<typeof FlatClassComplexity>
export const FlatMethodComplexity = FlatClassComplexity
export type FlatMethodComplexity = FlatClassComplexity

export const FlatComplexityResult = Record({
  source: String,
  classes: Array(FlatClassComplexity),
  methods: Array(FlatMethodComplexity),
})
export type FlatComplexityResult = Static<typeof FlatComplexityResult>

export const FlatComplexityResults = Record({
  results: Array(FlatComplexityResult),
})
export type FlatComplexityResults = Static<typeof FlatComplexityResults>

export const FeatureValue = Record({
  featureMap: Dictionary(Number),
  importList: Array(String),
  typeList: Array(String),
  identifierList: Array(String),
})
export type FeatureValue = Static<typeof FeatureValue>

export const FlatClassFeatures = Record({
  name: String,
  path: String,
  range: SourceRange,
  features: FeatureValue,
})
export type FlatClassFeatures = Static<typeof FlatClassFeatures>
export const FlatMethodFeatures = FlatClassFeatures
export type FlatMethodFeatures = FlatClassFeatures

export const FlatFeaturesResult = Record({
  source: String,
  classes: Array(FlatClassFeatures),
  methods: Array(FlatMethodFeatures),
})
export type FlatFeaturesResult = Static<typeof FlatFeaturesResult>

export const FlatFeaturesResults = Record({
  results: Array(FlatFeaturesResult),
})
export type FlatFeaturesResults = Static<typeof FlatFeaturesResults>

export const MutationLocation = Record({
  start: Number,
  end: Number,
  line: String,
  startLine: Number,
  endLine: Number,
})
export type MutationLocation = Static<typeof MutationLocation>

export const AppliedMutation = Record({
  mutationType: String,
  location: MutationLocation,
  original: String,
  mutated: String,
  linesChanged: Number,
})
export type AppliedMutation = Static<typeof AppliedMutation>

export const MutatedSource = Record({
  mutatedSource: String,
  mutatedSources: Dictionary(String),
  mutation: AppliedMutation,
})
export type MutatedSource = Static<typeof MutatedSource>

export const MutationsResults = Record({
  source: Dictionary(String),
  mutatedSources: Array(MutatedSource),
})
export type MutationsResults = Static<typeof MutationsResults>

export const ThrownException = Record({
  klass: String,
  stacktrace: String,
}).And(
  Partial({
    message: String,
  })
)
export type ThrownException = Static<typeof ThrownException>

export const Console = Union(Literal("STDOUT"), Literal("STDERR"))
export type Console = Static<typeof Console>

export const OutputLine = Record({
  console: Console,
  line: String,
  timestamp: String.withConstraint((s) => !isNaN(Date.parse(s))),
}).And(
  Partial({
    thread: Number,
  })
)
export type OutputLine = Static<typeof OutputLine>

export const PermissionRequest = Record({
  permission: Permission,
  granted: Boolean,
})
export type PermissionRequest = Static<typeof PermissionRequest>

export const SourceTaskResults = Record({
  klass: String,
  method: String,
  timeout: Boolean,
  outputLines: Array(OutputLine),
  permissionRequests: Array(PermissionRequest),
  interval: Interval,
  executionInterval: Interval,
  truncatedLines: Number,
}).And(
  Partial({
    returned: String,
    threw: ThrownException,
  })
)
export type SourceTaskResults = Static<typeof SourceTaskResults>

export const ContainerExecutionResults = Record({
  klass: String,
  method: String,
  timeout: Boolean,
  outputLines: Array(OutputLine),
  interval: Interval,
  executionInterval: Interval,
  truncatedLines: Number,
}).And(
  Partial({
    exitcode: Number,
  })
)
export type ContainerExecutionResults = Static<typeof ContainerExecutionResults>

export const CompletedTasks = Partial({
  snippet: Snippet,
  template: TemplatedSourceResult,
  compilation: CompiledSourceResult,
  kompilation: CompiledSourceResult,
  checkstyle: CheckstyleResults,
  ktlint: KtlintResults,
  complexity: FlatComplexityResults,
  features: FlatFeaturesResults,
  execution: SourceTaskResults,
  cexecution: ContainerExecutionResults,
  mutations: MutationsResults,
})
export type CompletedTasks = Static<typeof CompletedTasks>

export const TemplatingError = Record({
  name: String,
  line: Number,
  column: Number,
  message: String,
})
export type TemplatingError = Static<typeof TemplatingError>

export const TemplatingFailed = Record({
  errors: Array(TemplatingError),
})
export type TemplatingFailed = Static<typeof TemplatingFailed>

export const SnippetTransformationError = Record({
  line: Number,
  column: Number,
  message: String,
})
export type SnippetTransformationError = Static<typeof SnippetTransformationError>

export const SnippetTransformationFailed = Record({
  errors: Array(SnippetTransformationError),
})
export type SnippetTransformationFailed = Static<typeof SnippetTransformationFailed>

export const CompilationError = Record({
  message: String,
}).And(
  Partial({
    location: SourceLocation,
  })
)
export type CompilationError = Static<typeof CompilationError>

export const CompilationFailed = Record({
  errors: Array(CompilationError),
})
export type CompilationFailed = Static<typeof CompilationFailed>

export const CheckstyleFailed = Record({
  errors: Array(CheckstyleError),
})
export type CheckstyleFailed = Static<typeof CheckstyleFailed>

export const KtlintFailed = Record({
  errors: Array(KtlintError),
})
export type KtlintFailed = Static<typeof KtlintFailed>

export const SourceError = Record({
  message: String,
}).And(
  Partial({
    location: SourceLocation,
  })
)
export type SourceError = Static<typeof SourceError>

export const ComplexityFailed = Record({
  errors: Array(SourceError),
})
export type ComplexityFailed = Static<typeof ComplexityFailed>

export const FeaturesFailed = Record({
  errors: Array(SourceError),
})
export type FeaturesFailed = Static<typeof FeaturesFailed>

export const MutationsFailed = Record({
  errors: Array(SourceError),
})
export type MutationsFailed = Static<typeof MutationsFailed>

export const ExecutionFailedResult = Partial({
  classNotFound: String,
  methodNotFound: String,
})
export type ExecutionFailedResult = Static<typeof ExecutionFailedResult>

export const FailedTasks = Partial({
  template: TemplatingFailed,
  snippet: SnippetTransformationFailed,
  compilation: CompilationFailed,
  kompilation: CompilationFailed,
  checkstyle: CheckstyleFailed,
  ktlint: KtlintFailed,
  complexity: ComplexityFailed,
  execution: ExecutionFailedResult,
  cexecution: ExecutionFailedResult,
  features: FeaturesFailed,
  mutations: MutationsFailed,
})
export type FailedTasks = Static<typeof FailedTasks>

export const Response = Record({
  request: Request,
  status: ServerStatus,
  completed: CompletedTasks,
  completedTasks: Array(Task),
  failed: FailedTasks,
  failedTasks: Array(Task),
}).And(
  Partial({
    email: String,
    audience: Array(String),
  })
)
export type Response = Static<typeof Response>
