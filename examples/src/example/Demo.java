package example;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;

/** Minimal bytecode fixtures, not an executable Android application. */
public class Demo {
    public void directFlow(Intent intent, SQLiteDatabase db) {
        String query = intent.getStringExtra("query");
        db.execSQL(query);
    }
    public void transformedFlow(Intent intent, SQLiteDatabase db) {
        String query = intent.getStringExtra("query").trim();
        db.execSQL(query);
    }
    public void overwritten(Intent intent, SQLiteDatabase db) {
        String query = intent.getStringExtra("query");
        query = "DELETE FROM cache WHERE expired = 1";
        db.execSQL(query);
    }
    public void boundValue(Intent intent, SQLiteDatabase db) {
        String value = intent.getStringExtra("name");
        db.execSQL("DELETE FROM cache WHERE name = ?", new Object[] { value });
    }
}
