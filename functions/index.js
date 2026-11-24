"use strict";
const { initializeApp } = require("firebase-admin/app");
initializeApp();
const license = require("./license-status");
const admin = require("./account-admin");
exports.getMyLicenseStatus = license.getMyLicenseStatus;
exports.adminGetAccount = admin.adminGetAccount;
exports.adminUpdateAccount = admin.adminUpdateAccount;

exports.adminListAccounts = admin.adminListAccounts;
