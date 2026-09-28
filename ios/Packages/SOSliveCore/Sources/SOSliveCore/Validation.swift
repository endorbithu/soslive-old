import Foundation

/// Emergency contact phone numbers - international format, same rule as the Android app and the API.
public enum SosContacts {
    public static let maxContacts = 10

    public enum ParseResult: Equatable {
        case valid([String])
        case invalid([String])
        case tooMany
    }

    public static func isValid(_ number: String) -> Bool {
        number.range(of: #"^\+[0-9]{10,13}$"#, options: .regularExpression) != nil
    }

    /// Splits user input (comma / semicolon / newline separated), strips spaces and dashes.
    public static func parse(_ input: String) -> ParseResult {
        var seen = Set<String>()
        let numbers = input
            .components(separatedBy: CharacterSet(charactersIn: ",;\n"))
            .map { $0.replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "").trimmed }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
        let invalid = numbers.filter { !isValid($0) }
        if !invalid.isEmpty { return .invalid(invalid) }
        if numbers.count > maxContacts { return .tooMany }
        return .valid(numbers)
    }

    public static func format(_ numbers: [String]) -> String {
        numbers.joined(separator: "\n")
    }
}

public enum Email {
    public static func isValid(_ value: String) -> Bool {
        value.trimmed.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil
    }
}

public enum SimulatedSso {
    /// Token format the mock backend accepts when a provider is not configured.
    public static func token(email: String, displayName: String) -> String {
        let name = displayName.trimmed
        return "mock:\(email.trimmed)" + (name.isEmpty ? "" : "|\(name)")
    }
}

public enum SosMessage {
    public static func build(template: String, fallback: String, shareUrl: String) -> String {
        let text = template.trimmed.isEmpty ? fallback : template
        return "\(text.trimmed) - \(shareUrl)"
    }
}
