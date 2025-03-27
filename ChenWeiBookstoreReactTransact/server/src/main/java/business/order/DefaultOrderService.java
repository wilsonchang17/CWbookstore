package business.order;

import api.ApiException;
import business.book.Book;

import business.book.BookDao;
import business.cart.ShoppingCart;
import business.cart.ShoppingCartItem;
import business.customer.CustomerDao;
import business.customer.CustomerForm;
import business.customer.Customer;
import java.util.List;

import java.time.YearMonth;
import java.util.regex.Pattern;
import java.util.Date;
import java.util.Calendar;

import java.sql.Connection;
import java.sql.SQLException;
import business.ApplicationContext;

import java.util.concurrent.ThreadLocalRandom;

import java.time.YearMonth;

import java.util.List;

import business.JdbcUtils;
import business.BookstoreDbException;


public class DefaultOrderService implements OrderService {

	private BookDao bookDao;
	private CustomerDao customerDao;
	private OrderDao orderDao;
	private LineItemDao lineItemDao;

	public void setBookDao(BookDao bookDao) {
		this.bookDao = bookDao;
	}
	public void setCustomerDao(CustomerDao customerDao) {
		this.customerDao = customerDao;
	}

	public void setOrderDao(OrderDao orderDao) {
		this.orderDao = orderDao;
	}

	public void setLineItemDao(LineItemDao lineItemDao) {
		this.lineItemDao = lineItemDao;
	}
	@Override
	public OrderDetails getOrderDetails(long orderId) {
		Order order = orderDao.findByOrderId(orderId);
		Customer customer = customerDao.findByCustomerId(order.customerId());
		List<LineItem> lineItems = lineItemDao.findByOrderId(orderId);
		List<Book> books = lineItems
				.stream()
				.map(lineItem -> bookDao.findByBookId(lineItem.bookId()))
				.toList();
		return new OrderDetails(order, customer, lineItems, books);
	}
	@Override
	public long placeOrder(CustomerForm customerForm, ShoppingCart cart) {
		try {
			System.out.println("Starting order placement");
			validateCustomer(customerForm);
			validateCart(cart);

			try (Connection connection = JdbcUtils.getConnection()) {
				Date ccExpDate = getCardExpirationDate(
						customerForm.getCcExpiryMonth(),
						customerForm.getCcExpiryYear());

				long orderId = performPlaceOrderTransaction(
						customerForm.getName(),
						customerForm.getAddress(),
						customerForm.getPhone(),
						customerForm.getEmail(),
						customerForm.getCcNumber(),
						ccExpDate, cart, connection);

				System.out.println("Order ID returned: " + orderId);
				return orderId;
			} catch (SQLException e) {
				System.out.println("SQL Exception: " + e.getMessage());
				e.printStackTrace();
				throw new BookstoreDbException("Error during close connection for customer order", e);
			}
		} catch (Exception e) {
			System.out.println("General Exception: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}
	private Date getCardExpirationDate(String monthString, String yearString) {
		int month = Integer.parseInt(monthString);
		int year = Integer.parseInt(yearString);

		Calendar cal = Calendar.getInstance();
		cal.set(Calendar.YEAR, year);
		cal.set(Calendar.MONTH, month - 1);  // Calendar months are 0-based
		cal.set(Calendar.DAY_OF_MONTH, 1);

		// Convert util.Date to sql.Date
		return new java.sql.Date(cal.getTimeInMillis());
	}

	private long performPlaceOrderTransaction(
			String name, String address, String phone,
			String email, String ccNumber, Date date,
			ShoppingCart cart, Connection connection) {
		try {
			connection.setAutoCommit(false);
			System.out.println("Creating customer...");
			long customerId = customerDao.create(
					connection, name, address, phone, email,
					ccNumber, date);
			System.out.println("Customer created with ID: " + customerId);

			System.out.println("Creating order...");
			long customerOrderId = orderDao.create(
					connection,
					cart.getComputedSubtotal() + cart.getSurcharge(),
					generateConfirmationNumber(), customerId);
			System.out.println("Order created with ID: " + customerOrderId);

			System.out.println("Creating line items...");
			for (ShoppingCartItem item : cart.getItems()) {
				lineItemDao.create(connection, item.getBookId(), customerOrderId, item.getQuantity());
			}
			System.out.println("Line items created");

			connection.commit();
			System.out.println("Transaction committed");
			return customerOrderId;
		} catch (Exception e) {
			System.out.println("Transaction failed: " + e.getMessage());
			e.printStackTrace();
			try {
				System.out.println("Attempting rollback...");
				connection.rollback();
			} catch (SQLException e1) {
				System.out.println("Rollback failed: " + e1.getMessage());
				throw new BookstoreDbException("Failed to roll back transaction", e1);
			}
			throw new BookstoreDbException("Failed to place order", e);
		}
	}

	private int generateConfirmationNumber() {
		return ThreadLocalRandom.current().nextInt(999999999);
	}
	private void validateCustomer(CustomerForm customerForm) {
		if (customerForm == null) {
			throw new ApiException.ValidationFailure("customerForm", "Customer information is missing.");
		}

		validateField(customerForm.getName(), "name", 4, 45);

		validateField(customerForm.getAddress(), "address", 4, 45);

		validatePhone(customerForm.getPhone());

		validateEmail(customerForm.getEmail());

		validateCreditCardNumber(customerForm.getCcNumber());

		validateExpiryDate(customerForm.getCcExpiryMonth(), customerForm.getCcExpiryYear());
	}

	private void validateCart(ShoppingCart cart) {
		if (cart == null || cart.getItems() == null || cart.getItems().isEmpty()) {
			throw new ApiException.ValidationFailure("cart", "Cart must contain at least one item.");
		}

		for (ShoppingCartItem item : cart.getItems()) {
			if (item.getQuantity() <= 0 || item.getQuantity() > 99) {
				throw new ApiException.ValidationFailure("quantity", "Quantity must be between 1 and 99 for book ID: " + item.getBookForm().getBookId());
			}

			Book databaseBook = bookDao.findByBookId(item.getBookForm().getBookId());
			if (databaseBook == null) {
				throw new ApiException.ValidationFailure("bookId", "Book with ID " + item.getBookForm().getBookId() + " does not exist.");
			}

			if (item.getBookForm().getPrice() != databaseBook.price()) {
				throw new ApiException.ValidationFailure("price", "Price mismatch for book ID: " + item.getBookForm().getBookId());
			}

			if (item.getBookForm().getCategoryId() != databaseBook.categoryId()) {
				throw new ApiException.ValidationFailure("category", "Category mismatch for book ID: " + item.getBookForm().getBookId());
			}
		}
	}

	private void validateField(String field, String fieldName, int minLength, int maxLength) {
		if (field == null) {
			throw new ApiException.ValidationFailure(fieldName, capitalize(fieldName) + " is missing.");
		}
		String trimmedField = field.trim();
		if (trimmedField.isEmpty()) {
			throw new ApiException.ValidationFailure(fieldName, capitalize(fieldName) + " is empty.");
		}
		if (trimmedField.length() < minLength || trimmedField.length() > maxLength) {
			throw new ApiException.ValidationFailure(fieldName, capitalize(fieldName) + " must be between " + minLength + " and " + maxLength + " characters.");
		}
	}

	private void validatePhone(String phone) {
		if (phone == null) {
			throw new ApiException.ValidationFailure("phone", "Phone number is missing.");
		}
		String trimmedPhone = phone.trim();
		if (trimmedPhone.isEmpty()) {
			throw new ApiException.ValidationFailure("phone", "Phone number is empty.");
		}
		String sanitizedPhone = trimmedPhone.replaceAll("[\\s\\-()]", "");
		if (!sanitizedPhone.matches("\\d{10}")) {
			throw new ApiException.ValidationFailure("phone", "Phone number must be exactly 10 digits.");
		}
	}

	// 验证电子邮件
	private void validateEmail(String email) {
		if (email == null) {
			throw new ApiException.ValidationFailure("email", "Email is missing.");
		}
		String trimmedEmail = email.trim();
		if (trimmedEmail.isEmpty()) {
			throw new ApiException.ValidationFailure("email", "Email is empty.");
		}
		if (trimmedEmail.contains(" ") || !trimmedEmail.contains("@") || trimmedEmail.endsWith(".")) {
			throw new ApiException.ValidationFailure("email", "Invalid email format.");
		}
	}

	private void validateCreditCardNumber(String ccNumber) {
		if (ccNumber == null) {
			throw new ApiException.ValidationFailure("ccNumber", "Credit card number is missing.");
		}
		String trimmedCcNumber = ccNumber.trim();
		if (trimmedCcNumber.isEmpty()) {
			throw new ApiException.ValidationFailure("ccNumber", "Credit card number is empty.");
		}
		String sanitizedCcNumber = trimmedCcNumber.replaceAll("[\\s\\-]", "");
		if (!sanitizedCcNumber.matches("\\d{14,16}")) {
			throw new ApiException.ValidationFailure("ccNumber", "Credit card number must be between 14 and 16 digits and contain only digits.");
		}
	}

	private void validateExpiryDate(String ccExpiryMonth, String ccExpiryYear) {
		if (ccExpiryMonth == null || ccExpiryMonth.trim().isEmpty()) {
			throw new ApiException.ValidationFailure("ccExpiryMonth", "Expiry month is missing or empty.");
		}
		if (ccExpiryYear == null || ccExpiryYear.trim().isEmpty()) {
			throw new ApiException.ValidationFailure("ccExpiryYear", "Expiry year is missing or empty.");
		}
		try {
			if (!ccExpiryMonth.matches("\\d+") || !ccExpiryYear.matches("\\d+")) {
				throw new ApiException.ValidationFailure("ccExpiryDate", "Expiry date must contain valid numbers.");
			}

			int month = Integer.parseInt(ccExpiryMonth);
			int year = Integer.parseInt(ccExpiryYear);

			if (month < 1 || month > 12) {
				throw new ApiException.ValidationFailure("ccExpiryMonth", "Expiry month must be between 1 and 12.");
			}

			YearMonth expiryDate = YearMonth.of(year, month);
			YearMonth currentDate = YearMonth.now();

			if (expiryDate.isBefore(currentDate)) {
				throw new ApiException.ValidationFailure("ccExpiryDate", "Expiry date cannot be in the past.");
			}

		} catch (NumberFormatException e) {
			throw new ApiException.ValidationFailure("ccExpiryDate", "Expiry date must contain valid numbers.");
		}
	}

	private String capitalize(String str) {
		if (str == null || str.isEmpty()) return str;
		return str.substring(0, 1).toUpperCase() + str.substring(1);
	}

}