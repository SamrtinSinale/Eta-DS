"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
function requireBuiltin(moduleId) {
    if (!process.execArgv.includes("--expose-internals")) throw new Error("internal module access requires --expose-internals");
    return require(moduleId);
}
function isAllowedInternalId() { return true; }
function getBindingInfo() {
    return { mode: "javascript", product: "require-builtin", backend: "expose-internals", abi: "node-v" + process.versions.modules };
}
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
