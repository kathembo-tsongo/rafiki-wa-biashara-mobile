package com.example.llama

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : Activity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        findViewById<ImageButton>(R.id.back_button).setOnClickListener { finish() }

        val store = ConversationStore(applicationContext)
        val summaries = store.list()

        val list = findViewById<RecyclerView>(R.id.history_list)
        val emptyState = findViewById<TextView>(R.id.empty_state)

        if (summaries.isEmpty()) {
            list.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = HistoryAdapter(summaries) { id ->
                val resultIntent = Intent()
                resultIntent.putExtra(EXTRA_CONVERSATION_ID, id)
                setResult(RESULT_OK, resultIntent)
                finish()
            }
        }
    }
}

private class HistoryAdapter(
    private val items: List<ConversationSummary>,
    private val onClick: (String) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.history_title)
        val date: TextView = view.findViewById(R.id.history_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.date.text = dateFormat.format(Date(item.timestampMillis))
        holder.itemView.setOnClickListener { onClick(item.id) }
    }

    override fun getItemCount(): Int = items.size
}
