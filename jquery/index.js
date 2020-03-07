import "./jeed.js";

hljs.initHighlightingOnLoad();

$("code.lang-java").jeed("java", process.env.JEED);
$("code.lang-kotlin").jeed("kotlin", process.env.JEED);
