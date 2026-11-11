import XCTest

final class OnboardingTests: XCTestCase {
    func testOnboardingAndCancelWithoutCreatingWallet() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing"]
        app.launch()
        XCTAssertTrue(app.staticTexts["Your Lightning Wallet"].waitForExistence(timeout: 5))
        app.buttons["Create your wallet"].tap()
        XCTAssertTrue(app.buttons["Remote Control"].exists)
        app.buttons["Easy mode"].tap()
        app.buttons["Continue"].tap()
        XCTAssertTrue(app.staticTexts["Name your wallet"].waitForExistence(timeout: 3))
        XCTAssertEqual(app.textFields.firstMatch.value as? String, "Neutrino")
        app.buttons["Cancel setup"].tap()
        XCTAssertTrue(app.staticTexts["Your Lightning Wallet"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "iPhone onboarding"; screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
