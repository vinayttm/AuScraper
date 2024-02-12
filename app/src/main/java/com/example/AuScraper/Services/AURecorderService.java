package com.example.AuScraper.Services;

import static com.example.AuScraper.Utils.AccessibilityUtil.*;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import com.example.AuScraper.MainActivity;
import com.example.AuScraper.Repository.QueryUPIStatus;
import com.example.AuScraper.Utils.AES;
import com.example.AuScraper.Utils.CaptureTicker;
import com.example.AuScraper.Utils.Config;
import com.example.AuScraper.Utils.SharedData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class AURecorderService extends AccessibilityService {
    boolean loginOnce = true;
    boolean myTransactions = true;
    final CaptureTicker ticker = new CaptureTicker(this::processTickerEvent);
    int appNotOpenCounter = 0;

    @Override
    protected void onServiceConnected() {
        ticker.startChecking();
        super.onServiceConnected();
    }

    private void processTickerEvent() {
        Log.d("Ticker", "Processing Event");
        Log.d("Flags", printAllFlags());
        ticker.setNotIdle();

        // if (!SharedData.startedChecking) return;
        if (!MainActivity.isAccessibilityServiceEnabled(this, this.getClass())) {
            return;
        }

        AccessibilityNodeInfo rootNode = getTopMostParentNode(getRootInActiveWindow());
        if (rootNode != null) {
            listAllTextsInActiveWindow(getTopMostParentNode(getRootInActiveWindow()));
            checkForSessionExpiry();
            if (findNodeByPackageName(rootNode, Config.packageName) == null) {
                if (appNotOpenCounter > 4) {
                    Log.d("App Status", "Not Found");
                    relaunchApp();
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    appNotOpenCounter = 0;
                    return;
                }
                appNotOpenCounter++;
            } else {
                Log.d("App Status", "Found");
                rootNode.refresh();
                checkForSessionExpiry();
                login();
                enterPin();
                if ((listAllTextsInActiveWindow(getTopMostParentNode(getRootInActiveWindow())).contains("Accounts"))) {
                    menuButton();
                }

                if (listAllTextsInActiveWindow(getTopMostParentNode(getRootInActiveWindow())).contains("Account Statement")) {
                    try {
                        Thread.sleep(4000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    AccessibilityNodeInfo targetNode = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Account Statement", true, false);
                    if (targetNode != null) {
                        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        ticker.setNotIdle();
                    }
                }

                if (listAllTextsInActiveWindow(getTopMostParentNode(getRootInActiveWindow())).contains("Current month")) {
                    readTransactions();
                }
                backingProcess();
            }
            rootNode.recycle();
        }
    }

    private void login() {
        AccessibilityNodeInfo loginBtn = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Login", false, false);
        if (loginBtn != null) {
            Rect outBounds = new Rect();
            loginBtn.getBoundsInScreen(outBounds);
            performTap(outBounds.centerX(), outBounds.centerY());
        }
    }

    private void relaunchApp() {
        // Might fail not tested
        Log.d("Action", "Relaunching App");
        if (MainActivity.isAccessibilityServiceEnabled(this, this.getClass())) {
            new QueryUPIStatus(() -> {
                Intent intent = getPackageManager().getLaunchIntentForPackage(Config.packageName);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
            }, () -> {
                Toast.makeText(this, "Scrapper inactive", Toast.LENGTH_SHORT).show();
            }).evaluate();
        }
    }

    private void backingProcess() {
        if (myTransactions) {
            AccessibilityNodeInfo c1 = findNodeByContentDescription(getTopMostParentNode(getRootInActiveWindow()), "c1");
            if (c1 != null) {
                boolean isClicked = c1.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (isClicked) {
                    myTransactions = false;
                }
            }
        }
    }
    

    int targetIndex = -1;

    private void readTransactions() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        JSONArray output = new JSONArray();
        AccessibilityNodeInfo currentMonth = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Current month", true, false);
        if (currentMonth != null) {
            List<String> unmodifiedList = listAllTextsInActiveWindow(getTopMostParentNode(getRootInActiveWindow()));

            unmodifiedList.removeIf(String::isEmpty);
            List<String> modifieldList = new ArrayList<>();
            for (String item : unmodifiedList) {
                if (!item.isEmpty()) {
                    modifieldList.add(item);
                }
            }
            for (int i = 0; i < modifieldList.size(); i++) {
                if (modifieldList.get(i).contains("loangridrptr0")) {
                    targetIndex = i;
                    break;
                }
            }
            List<String> unfilterList = modifieldList.subList(targetIndex, modifieldList.size());
            unfilterList.remove(0);
            int size = unfilterList.size();
            unfilterList.subList(size - 4, size).clear();
            System.out.println("UnFilterList " + unfilterList.toString());
            if (unfilterList.size() > 0) {
                Log.d("API BODY", output.toString());
                JSONObject result = new JSONObject();
                try {
                    result.put("Result", AES.encrypt(output.toString()));
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }

                for (int i = 0; i < unfilterList.size(); i += 6) {
                    JSONObject jsonObject = new JSONObject();
                    String amount = unfilterList.get(i).replace("₹", "").replaceAll("\\s+", "");
                    String date = unfilterList.get(i + 1);
                    String totalBalance = unfilterList.get(i + 4).replace("₹", "").replaceAll("\\s+", "");
                    String description = unfilterList.get(i + 5);
                    try {
                        jsonObject.put("Description", extractUTRFromDesc(description));
                        jsonObject.put("UPIId", getUPIId(description));
                        jsonObject.put("CreatedDate", date);
                        jsonObject.put("Amount", amount.trim());
                        jsonObject.put("RefNumber", extractUTRFromDesc(description));
                        jsonObject.put("AccountBalance", totalBalance.trim());
                        jsonObject.put("BankName", Config.bankName + Config.bankLoginId);
                        jsonObject.put("BankLoginId", Config.bankLoginId);
                        output.put(jsonObject);

                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }
                System.out.println("JsonData" + output.toString());
                myTransactions = true;


//            new QueryUPIStatus(() -> {
//                new SaveBankTransaction(() -> {
//                    Rect outBounds = new Rect();
//                    getTopMostParentNode(getRootInActiveWindow()).getBoundsInScreen(outBounds);
//                    swipe(outBounds.centerX(), outBounds.centerY(), outBounds.centerX(), 0, 1500);
//                }, () -> {
//                    Rect outBounds = new Rect();
//                    getRootInActiveWindow().getBoundsInScreen(outBounds);
//                    int startX = outBounds.width() / 2;
//                    int startY = outBounds.height() / 2;
//                    int endY = outBounds.height();
//                    swipe(startX, startY, startX, endY, 1000);
//                }).evaluate(result.toString());
//                new UpdateDateForScrapper().evaluate();
//            }, () -> {
//            }).evaluate();

            }
        }
    }


    public void menuButton() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        AccessibilityNodeInfo accountsTextInfo = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Accounts", false, false);
        if (accountsTextInfo != null) {
            Rect clickArea = new Rect();
            accountsTextInfo.getBoundsInScreen(clickArea);
            performTap(clickArea.centerX(), clickArea.centerY() - 100);
        }
    }


    public void enterPin() {
        String loginPin = "3939";
        AccessibilityNodeInfo mPinText = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Enter mPIN", false, false);
        if (mPinText != null) {
            AccessibilityNodeInfo mPinTextField = findNodeByClassName(getTopMostParentNode(getRootInActiveWindow()), "android.widget.EditText");
            if (mPinTextField != null) {
                mPinTextField.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            List<Map<String, Object>> jsonArray = new ArrayList<>();
            Map<String, Object> one = new HashMap<>();
            one.put("x", 96);
            one.put("y", 1083);
            one.put("pin", "1");

            Map<String, Object> two = new HashMap<>();
            two.put("x", 276);
            two.put("y", 1088);
            two.put("pin", "2");


            Map<String, Object> three = new HashMap<>();
            three.put("x", 449);
            three.put("y", 1087);
            three.put("pin", "3");


            Map<String, Object> four = new HashMap<>();
            four.put("x", 104);
            four.put("y", 1201);
            four.put("pin", "4");

            Map<String, Object> five = new HashMap<>();
            five.put("x", 275);
            five.put("y", 1206);
            five.put("pin", "5");

            Map<String, Object> six = new HashMap<>();
            six.put("x", 445);
            six.put("y", 1207);
            six.put("pin", "6");

            Map<String, Object> seven = new HashMap<>();
            seven.put("x", 89);
            seven.put("y", 1334);
            seven.put("pin", "7");

            Map<String, Object> eight = new HashMap<>();
            eight.put("x", 259);
            eight.put("y", 1311);
            eight.put("pin", "8");

            Map<String, Object> nine = new HashMap<>();
            nine.put("x", 448);
            nine.put("y", 1323);
            nine.put("pin", "9");

            Map<String, Object> zero = new HashMap<>();
            zero.put("x", 268);
            zero.put("y", 1439);
            zero.put("pin", "0");

            jsonArray.add(one);
            jsonArray.add(two);
            jsonArray.add(three);
            jsonArray.add(four);
            jsonArray.add(five);
            jsonArray.add(six);
            jsonArray.add(seven);
            jsonArray.add(eight);
            jsonArray.add(nine);
            jsonArray.add(zero);

            for (char c : loginPin.toCharArray()) {
                for (Map<String, Object> json : jsonArray) {
                    String pinValue = (String) json.get("pin");
                    if (pinValue != null && json.get("x") != null && json.get("y") != null) {
                        if (pinValue.equals(String.valueOf(c))) {
                            int x = Integer.parseInt(json.get("x").toString());
                            int y = Integer.parseInt(json.get("y").toString());
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            System.out.println("Clicked on X : " + x + " PIN " + pinValue);
                            System.out.println("Clicked on Y : " + y + " PIN " + pinValue);
                            performTap(x, y);

                        }
                    }
                }
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }


    public void checkForSessionExpiry() {
        AccessibilityNodeInfo targetNode1 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Session Expired! Please LOGIN again", false, false);
        AccessibilityNodeInfo targetNode2 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Information", false, false);
        AccessibilityNodeInfo targetNode3 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Unable to process the request,Please try again.", false, false);
        AccessibilityNodeInfo targetNode4 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "MPin cannot be blank.", false, false);
        AccessibilityNodeInfo targetNode5 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "t responding", true, false);
        AccessibilityNodeInfo targetNode6 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Please download latest app from playStore", true, false);
        AccessibilityNodeInfo targetNode7 = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Session Time Out. Please try again", true, false);
        if (targetNode6 != null) {
            AccessibilityNodeInfo okButtonNode = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "OK", false, true);
            okButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            okButtonNode.recycle();
            ticker.setNotIdle();
            relaunchApp();
        }

        if (targetNode5 != null) {
            Log.d("Inside", "Close app");
            AccessibilityNodeInfo okButtonNode = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Close app", false, true);
            okButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            okButtonNode.recycle();
            ticker.setNotIdle();

        }

        if (targetNode1 != null || targetNode3 != null) {
            AccessibilityNodeInfo okButtonNode = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "OK", false, true);
            okButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            okButtonNode.recycle();
            loginOnce = false;
            myTransactions = true;
            if (targetNode1 != null) {
                targetNode1.recycle();
            } else {
                targetNode3.recycle();
            }
            ticker.setNotIdle();
        }

        if (targetNode2 != null) {
            AccessibilityNodeInfo backButtonNode = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Back", false, true);
            if (backButtonNode != null) {
                backButtonNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                backButtonNode.recycle();
                ticker.setNotIdle();
                loginOnce = true;
                myTransactions = true;
            }
            targetNode2.recycle();
        }

        if (targetNode4 != null) {
            relaunchApp();
            targetNode4.recycle();
        }
        if (targetNode7 != null) {
            AccessibilityNodeInfo loginAgain = findNodeByText(getTopMostParentNode(getRootInActiveWindow()), "Login Again", true, false);
            if (loginAgain != null) {
                loginAgain.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        }
    }

    private String printAllFlags() {
        StringBuilder result = new StringBuilder();
        // Get the fields of the class
        Field[] fields = getClass().getDeclaredFields();

        for (Field field : fields) {
            field.setAccessible(true);
            String fieldName = field.getName();
            try {
                Object value = field.get(this);
                result.append(fieldName).append(": ").append(value).append("\n");
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
        return result.toString();
    }

    public void performTap(int x, int y) {
        Log.d("Accessibility", "Tapping " + x + " and " + y);
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(p, 0, 950));

        GestureDescription gestureDescription = gestureBuilder.build();

        boolean dispatchResult = false;
        dispatchResult = dispatchGesture(gestureDescription, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
            }
        }, null);
        Log.d("Dispatch Result", String.valueOf(dispatchResult));
    }

    public void swipe(float oldX, float oldY, float newX, float newY, long duration) {
        // Set up the Path by swiping from the old position coordinates to the new position coordinates.
        Path swipePath = new Path();
        swipePath.moveTo(oldX, oldY);
        swipePath.lineTo(newX, newY);

        GestureDescription.Builder gestureBuilder = new GestureDescription.Builder();
        gestureBuilder.addStroke(new GestureDescription.StrokeDescription(swipePath, 0, duration));

        boolean dispatchResult = dispatchGesture(gestureBuilder.build(), null, null);

        try {
            Thread.sleep(duration / 1000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public JSONObject getUploadData(List<String> paymentInfoArray) {
        //  [15-01-2024, Cr, 200.00, 1,200.10, TxnDataString]
        Log.d("PAYMENT INFO", paymentInfoArray.toString());

        // Heavily relying on index not being changed
        String date = paymentInfoArray.get(0);
        String amount = paymentInfoArray.get(2);
        String balance = paymentInfoArray.get(3);
        String transactionInfo = paymentInfoArray.get(4);

        if (paymentInfoArray.get(1).equals("Dr")) {
            amount = "-" + paymentInfoArray.get(2);
        }

        JSONObject jsonObject = new JSONObject();

        try {
            jsonObject.put("Description", extractUTRFromDesc(transactionInfo));
            jsonObject.put("UPIId", getUPIId(transactionInfo));
            jsonObject.put("CreatedDate", date);
            jsonObject.put("Amount", amount);
            jsonObject.put("RefNumber", extractUTRFromDesc(transactionInfo));
            jsonObject.put("AccountBalance", balance);
            jsonObject.put("BankName", Config.bankName + Config.bankLoginId);
            jsonObject.put("BankLoginId", Config.bankLoginId);

        } catch (JSONException e) {
            throw new RuntimeException(e);
        }

        return jsonObject;
    }

    private String getUPIId(String description) {
        try {
            if (!description.contains("@")) return "";
            String[] split = description.split("/");
            String value = null;
            value = Arrays.stream(split).filter(x -> x.contains("@")).findFirst().orElse(null);
            return value != null ? value : "";
        } catch (Exception ex) {
            Log.d("Exception", ex.getMessage());
            return "";
        }
    }

    private String extractUTRFromDesc(String description) {
        try {
            String[] split = description.split("/");
            String value = null;
            value = Arrays.stream(split).filter(x -> x.length() == 12).findFirst().orElse(null);
            if (value != null) {
                return value + " " + description;
            }
            return description;
        } catch (Exception e) {
            return description;
        }
    }

    // Unused AccessibilityService Callbacks
    @Override
    public void onInterrupt() {

    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

}
