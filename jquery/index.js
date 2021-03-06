import "./jeed.js";

hljs.initHighlightingOnLoad();

const runButton =
  '<button class="jeed play" style="position: relative; top: -22px; float: right; margin-bottom: -22px;"><i class="fa fa-play-circle"></i></button>';
const closeButton =
  '<button class="jeed play" style="position: absolute; right: 2px; top: 2px;"><i class="fa fa-close"></i></button>';

$("pre").jeed(process.env.JEED, {
  runButton,
  closeButton,
});
