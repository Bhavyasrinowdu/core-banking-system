import java.io.*;
import java.sql.*;
import java.util.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String msg) { super(msg); }
}
class AccountNotFoundException extends Exception {
    public AccountNotFoundException(String msg) { super(msg); }
}
class Transaction {
    private String id;
    private String timestamp;
    private String description;
    private double amount;
    private String category;
    public Transaction(String description, double amount, String category) {
        this.id = UUID.randomUUID().toString().substring(0, 5); // Short 5-character random ID
        this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        this.description = description;
        this.amount = amount;
        this.category = category;
    }
    public Transaction(String id, String timestamp, String description, double amount, String category) {
        this.id = id;
        this.timestamp = timestamp;
        this.description = description;
        this.amount = amount;
        this.category = category;
    }
    public String getId() { return id; }
    public String getTimestamp() { return timestamp; }
    public String getDescription() { return description; }
    public double getAmount() { return amount; }
    public String getCategory() { return category; }

    @Override
    public String toString() {
        return "[" + timestamp + "] ID: " + id + " | " + category + " | " + description + " | $" + amount;
    }
}
class Account {
    private String accountId;
    private String name;
    private String password;
    private double balance;
    private List<Transaction> history = new ArrayList<>();
    public Account(String accountId, String name, String password, double balance) {
        this.accountId = accountId;
        this.name = name;
        this.password = password;
        this.balance = balance;
    }
    public String getAccountId() { return accountId; }
    public String getName() { return name; }
    public String getPassword() { return password; }
    public double getBalance() { return balance; }
    public List<Transaction> getHistory() { return history; }

    public boolean verifyPassword(String input) { 
        return this.password.equals(input); 
    }
    public void deposit(Transaction tx) {
        this.balance += tx.getAmount();
        this.history.add(tx);
    }
    public void withdraw(Transaction tx) throws InsufficientFundsException {
        if (this.balance < Math.abs(tx.getAmount())) {
            throw new InsufficientFundsException("Error: You don't have enough money!");
        }
        this.balance -= Math.abs(tx.getAmount());
        this.history.add(tx);
    }
    public void loadOldTransaction(Transaction tx) {
        this.history.add(tx);
    }
}
class BankEngine {
    private Map<String, Account> memoryCache = new HashMap<>();
    private static final String DB_URL = "jdbc:sqlite:banking_system.db";

    public BankEngine() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.out.println("[Notice] Running in In-Memory mode because SQLite driver jar isn't in your folder.");
        }
        initDatabase();
        loadDatabaseIntoMemory();
    }
    private void initDatabase() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "acc_id TEXT PRIMARY KEY, " +
                    "username TEXT, " +
                    "pass TEXT, " +
                    "current_balance REAL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS tx_history (" +
                    "tx_id TEXT PRIMARY KEY, " +
                    "acc_id TEXT, " +
                    "time_stamp TEXT, " +
                    "details TEXT, " +
                    "amt REAL, " +
                    "tag TEXT)");
                    
        } catch (SQLException e) {
        }
    }
    public String createAccount(String name, String password, double initialDeposit) {
        String newId = "CORE-" + (memoryCache.size() + 1001);
        Account acc = new Account(newId, name, password, 0.0);
        
        Transaction initialTx = null;
        if (initialDeposit > 0) {
            initialTx = new Transaction("Initial Deposit", initialDeposit, "Income");
            acc.deposit(initialTx);
        }
        
        memoryCache.put(newId, acc);
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String userSql = "INSERT INTO users VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(userSql)) {
                ps.setString(1, newId);
                ps.setString(2, name);
                ps.setString(3, password);
                ps.setDouble(4, acc.getBalance());
                ps.executeUpdate();
            }
            if (initialTx != null) {
                String txSql = "INSERT INTO tx_history VALUES (?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(txSql)) {
                    ps.setString(1, initialTx.getId());
                    ps.setString(2, newId);
                    ps.setString(3, initialTx.getTimestamp());
                    ps.setString(4, initialTx.getDescription());
                    ps.setDouble(5, initialTx.getAmount());
                    ps.setString(6, initialTx.getCategory());
                    ps.executeUpdate();
                }
            }
        } catch (SQLException ignored) {}
        return newId;
    }
    public Account login(String id, String password) throws AccountNotFoundException, SecurityException {
        Account acc = memoryCache.get(id);
        if (acc == null) throw new AccountNotFoundException("Account ID does not exist!");
        if (!acc.verifyPassword(password)) throw new SecurityException("Incorrect Password!");
        return acc;
    }
    public void processTransaction(Account acc, double amount, String desc) throws InsufficientFundsException {
        Transaction tx;
        if (amount > 0) {
            tx = new Transaction(desc, amount, "Income");
            acc.deposit(tx);
        } else {
            tx = new Transaction(desc, amount, "Expense");
            acc.withdraw(tx);
        }
        try (Connection conn = DriverManager.getConnection(DB_URL)) {
            String updateSql = "UPDATE users SET current_balance = ? WHERE acc_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setDouble(1, acc.getBalance());
                ps.setString(2, acc.getAccountId());
                ps.executeUpdate();
            }
            String insertTxSql = "INSERT INTO tx_history VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertTxSql)) {
                ps.setString(1, tx.getId());
                ps.setString(2, acc.getAccountId());
                ps.setString(3, tx.getTimestamp());
                ps.setString(4, tx.getDescription());
                ps.setDouble(5, tx.getAmount());
                ps.setString(6, tx.getCategory());
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {}
    }
    private void loadDatabaseIntoMemory() {
        try (Connection conn = DriverManager.getConnection(DB_URL);
             Statement stmt = conn.createStatement()) {
            
            ResultSet rsUsers = stmt.executeQuery("SELECT * FROM users");
            while (rsUsers.next()) {
                String id = rsUsers.getString("acc_id");
                Account acc = new Account(
                    id, 
                    rsUsers.getString("username"), 
                    rsUsers.getString("pass"), 
                    rsUsers.getDouble("current_balance")
                );
                memoryCache.put(id, acc);
            }
            ResultSet rsTx = stmt.executeQuery("SELECT * FROM tx_history");
            while (rsTx.next()) {
                String accId = rsTx.getString("acc_id");
                Account acc = memoryCache.get(accId);
                if (acc != null) {
                    acc.loadOldTransaction(new Transaction(
                        rsTx.getString("tx_id"),
                        rsTx.getString("time_stamp"),
                        rsTx.getString("details"),
                        rsTx.getDouble("amt"),
                        rsTx.getString("tag")
                    ));
                }
            }
        } catch (SQLException ignored) {}
    }
}
public class BankingSystem {
    public static void main(String[] args) {
        BankEngine engine = new BankEngine();
        Scanner input = new Scanner(System.in);
        Account loggedInUser = null;

        while (true) {
            if (loggedInUser == null) {
                System.out.println("\n=== WELCOME TO THE BANK ===");
                System.out.println("1. Create New Account");
                System.out.println("2. Login to Account");
                System.out.println("3. Shut Down System");
                System.out.print("Choose an option: ");
                
                int choice = choiceReader(input);
                if (choice == 1) {
                    System.out.print("Enter Your Full Name: ");
                    String name = input.nextLine();
                    System.out.print("Set a Password/Pin: ");
                    String pass = input.nextLine();
                    System.out.print("Initial Deposit Amount ($): ");
                    double amount = doubleReader(input);
                    
                    String accountId = engine.createAccount(name, pass, amount);
                    System.out.println("SUCCESS! Save your ID: " + accountId);
                    
                } else if (choice == 2) {
                    System.out.print("Enter Account ID (e.g. CORE-1001): ");
                    String id = input.nextLine().trim(); // Removes accidental white spaces
                    System.out.print("Enter Your Password: ");
                    String pass = input.nextLine();
                    
                    try {
                        loggedInUser = engine.login(id, pass);
                        System.out.println("Welcome back, " + loggedInUser.getName() + "!");
                    } catch (Exception e) {
                        System.out.println("Login Failed: " + e.getMessage());
                    }
                } else if (choice == 3) {
                    System.out.println("Exiting banking app. Goodbye!");
                    break;
                }
            } else {
                System.out.println("\n=== ACCOUNT DASHBOARD (" + loggedInUser.getAccountId() + ") ===");
                System.out.println("Current Balance: $" + loggedInUser.getBalance());
                System.out.println("1. Deposit Money");
                System.out.println("2. Withdraw Money / Pay Expense");
                System.out.println("3. View Bank Statement Log");
                System.out.println("4. Logout");
                System.out.print("Choose an action: ");
                
                int action = choiceReader(input);
                if (action == 1) {
                    System.out.print("Deposit Amount ($): ");
                    double amt = doubleReader(input);
                    System.out.print("Enter Description (e.g. Salary): ");
                    String desc = input.nextLine();
                    try {
                        engine.processTransaction(loggedInUser, amt, desc);
                        System.out.println("Money added successfully!");
                    } catch (Exception e) {
                        System.out.println("Error: " + e.getMessage());
                    }
                } else if (action == 2) {
                    System.out.print("Withdrawal Amount ($): ");
                    double amt = doubleReader(input);
                    System.out.print("Enter Description (e.g. Starbucks): ");
                    String desc = input.nextLine();
                    try {
                        engine.processTransaction(loggedInUser, -amt, desc); // Negative value for expense
                        System.out.println("Money withdrawn successfully!");
                    } catch (Exception e) {
                        System.out.println("Transaction Blocked: " + e.getMessage());
                    }
                } else if (action == 3) {
                    System.out.println("\n--- YOUR TRANSACTION HISTORY ---");
                    if (loggedInUser.getHistory().isEmpty()) {
                        System.out.println("No transactions found.");
                    } else {
                        loggedInUser.getHistory().forEach(System.out::println);
                    }
                } else if (action == 4) {
                    System.out.println("Logged out securely.");
                    loggedInUser = null;
                }
            }
        }
        input.close();
    }
    private static int choiceReader(Scanner s) {
        try { return Integer.parseInt(s.nextLine()); } catch (Exception e) { return -1; }
    }

    private static double doubleReader(Scanner s) {
        try { return Double.parseDouble(s.nextLine()); } catch (Exception e) { return 0.0; }
    }
}