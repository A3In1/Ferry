import FerryLogic
import Testing

@Test(arguments: [
    ("123456 is your verification code for Instagram.", "123456"),
    ("G-482913 is your Google verification code.", "482913"),
    ("Your WhatsApp code: 123-456. Don't share this code with others", "123456"),
    ("Dear Customer, 482913 is the OTP for your txn of Rs. 5,000.00 at AMAZON on card ending 1234. "
        + "Valid for 10 mins. Do not share it with anyone. - HDFC Bank", "482913"),
    ("Your OTP for txn of INR 2500.00 at Flipkart is 739204. Valid for 5 minutes.", "739204"),
    ("Use 8841 as your login PIN. Expires in 10 minutes.", "8841"),
    ("Your Chase verification code is 55023187. Don't share it. Call 1-800-935-9935 if you didn't request it.", "55023187"),
    ("Telegram code: 41927\n\nYou can also tap on this link to log in: https://t.me/login/41927", "41927"),
    ("Your Uber code: 7193. Never share this code.", "7193"),
    ("PayPal: Your security code is 302948. Your code expires in 10 minutes.", "302948"),
    ("Amazon: 593021 is your one-time password (OTP). Don't share it.", "593021"),
    ("Use code 4821 on orders over $2000", "4821"),
])
func findsCodes(text: String, code: String) {
    #expect(oneTimeCode(in: text) == code)
}

@Test(arguments: [
    "Your package will arrive on Dec 12, 2024.",
    "Your verification was successful. Call us at +1 555-123-4567 for help.",
    "Your password was changed on 2024-03-15 at 14:30.",
    "Payment of $1234 received. Your PIN is unchanged.",
    "Rs 15000 debited from a/c XX4821 on 15-03-24. Not you? Call 18002586161. Never share your OTP.",
    "Your code red meeting is in room 101",
    "Hey, call me at 4155550123 about the code review",
    "Your verification code expires 12 March 2025",
    "Use promo code SAVE20 for 20% off",
    "Your PIN for card ending in 4417 has been reset.",
    "Enter zip code 94103 to continue",
    "Order #58213 shipped. Track it with your password.",
    "See you at 1530",
])
func ignoresNonCodes(text: String) {
    #expect(oneTimeCode(in: text) == nil)
}

@Test func sameNumbers() {
    #expect(sameNumber("+1 (555) 123-4567", "5551234567"))
    #expect(sameNumber("+44 7700 900123", "07700900123"))
    #expect(sameNumber("HDFCBK", "hdfcbk"))
    #expect(sameNumber("12345", "12345"))
    #expect(!sameNumber("5551234567", "5551234568"))
    #expect(!sameNumber("HDFCBK", "ICICIB"))
}

@Test func searching() {
    #expect(matchesSearch("", text: [], number: ""))
    #expect(matchesSearch("  ", text: ["Ann"], number: "123"))
    #expect(matchesSearch("ann", text: ["ANN Lee"], number: ""))
    #expect(matchesSearch("jose", text: ["José"], number: ""))
    #expect(matchesSearch("otp", text: ["Ann", "Your OTP is 1234"], number: ""))
    #expect(!matchesSearch("bob", text: ["Ann"], number: "98765"))
    #expect(matchesSearch("98765", text: [], number: "+91 98765-43210"))
    #expect(matchesSearch("987 65", text: [], number: "+919876543210"))
    #expect(!matchesSearch("555", text: [], number: "+919876543210"))
    #expect(matchesSearch("VM-HDFC", text: [], number: "VM-HDFCBK")) // sender names aren't numbers
    #expect(typedNumber(" +91 98765 ") == "+91 98765")
    #expect(typedNumber("12") == nil)
    #expect(typedNumber("ann") == nil)
    #expect(typedNumber("") == nil)
}
