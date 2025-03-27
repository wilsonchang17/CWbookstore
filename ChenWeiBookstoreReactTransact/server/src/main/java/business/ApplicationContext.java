package business;

import business.book.BookDao;
import business.book.BookDaoJdbc;
import business.category.CategoryDao;
import business.category.CategoryDaoJdbc;
import business.order.DefaultOrderService;
import business.order.OrderService;
import business.customer.CustomerDao;
import business.customer.CustomerDaoJdbc;
import business.order.OrderDao;
import business.order.OrderDaoJdbc;
import business.order.LineItemDao;
import business.order.LineItemDaoJdbc;

public class ApplicationContext {

    private static BookDao bookDao;
    private static CategoryDao categoryDao;
    private static OrderService orderService;
    private static CustomerDao customerDao;
    private static OrderDao orderDao;
    private static LineItemDao lineItemDao;
    public static final ApplicationContext INSTANCE = new ApplicationContext();

    private ApplicationContext() {
        // 初始化 DAO
        categoryDao = new CategoryDaoJdbc();
        bookDao = new BookDaoJdbc();
        customerDao = new CustomerDaoJdbc();
        orderDao = new OrderDaoJdbc();
        lineItemDao = new LineItemDaoJdbc();

        DefaultOrderService defaultOrderService = new DefaultOrderService();
        defaultOrderService.setBookDao(bookDao);
        defaultOrderService.setCustomerDao(customerDao);
        defaultOrderService.setOrderDao(orderDao);
        defaultOrderService.setLineItemDao(lineItemDao);

        orderService = defaultOrderService;
    }

    public static CategoryDao getCategoryDao() {
        return categoryDao;
    }

    public static BookDao getBookDao() {
        return bookDao;
    }

    public static OrderService getOrderService() {
        return orderService;
    }

    public static CustomerDao getCustomerDao() {
        return customerDao;
    }

    public static OrderDao getOrderDao() {
        return orderDao;
    }

    public static LineItemDao getLineItemDao() {
        return lineItemDao;
    }
}